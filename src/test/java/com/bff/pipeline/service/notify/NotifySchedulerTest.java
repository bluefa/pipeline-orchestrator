package com.bff.pipeline.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.model.NotifyClaim;
import com.bff.pipeline.repository.NotifyRepository;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link NotifyScheduler}의 로직 단위 테스트다. {@code PipelineSchedulerTest}처럼 스케줄러 스레드를
 * 띄우지 않고 {@code deliverOne}/{@code nextDelay}/{@code runSweep}을 직접 부른다.
 *
 * 검증하는 것:
 * - 켜고 끄기: 꺼져 있으면 start()가 루프 자체를 올리지 않는다(오너 결정 2026-07-09의 환경변수 채널).
 * - 전달에 쓰는 webhook 주소가 설정({@code settings.slackWebhookUrl()})에서 그대로 나온다.
 * - 성공/실패가 각각 onSuccess/onFailure로 기록된다. 실패는 한 바퀴를 죽이지 않고 재시도 기록으로 흡수된다.
 * - 실패 WARN 로그의 필수 필드: attempt는 기록(onFailure)의 반환값이고,
 *   기록이 무효 처리됐으면 stale-no-op으로 표시된다.
 * - 자동 재시도 중단 경보: 폴링 주기당 한 번만 countGivenUp을 조회하고, 0보다 크면 ERROR 로그를 남긴다
 *   (Logback ListAppender로 붙잡아 단언한다).
 * - 빈 바퀴의 대기 시간이 두 배씩 늘고, 일감이 생기면 처음으로 되돌아간다.
 *
 * 협력자는 이 repo 규약대로 fake로 대체한다. NotifyRepository는 인터페이스라서
 * countGivenUp만 응답하는 JDK Proxy fake를 쓴다.
 */
class NotifySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";

    @Test
    void aDisabledMasterSwitchNeverSchedulesTheLoop() {
        NotifyScheduler scheduler = new NotifyScheduler(claimerNeverCalled(), recordingSlackNotifier(),
                new RecordingWriteBack(), null, disabledSettings(), CLOCK);

        // 종료된 executor에 schedule하면 RejectedExecutionException이 난다. 알림이 꺼져 있으면
        // enabled 확인이 그 전에 끊어서(루프를 올리지 않아서) start가 조용히 돌아와야 한다.
        // 아래 enabled 케이스가 대조군이다.
        scheduler.stop();
        assertThatCode(scheduler::start).doesNotThrowAnyException();
    }

    @Test
    void anEnabledMasterSwitchSchedulesTheLoop() {
        NotifyScheduler scheduler = scheduler(claimerNeverCalled(), recordingSlackNotifier(),
                new RecordingWriteBack());

        scheduler.stop();
        assertThatThrownBy(scheduler::start).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void aSuccessfulDeliveryGoesToTheConfiguredWebhookAndIsRoutedToOnSuccess() {
        RecordingSlackNotifier slackNotifier = recordingSlackNotifier();
        RecordingWriteBack writeBack = new RecordingWriteBack();
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                slackNotifier, writeBack);

        assertThat(scheduler.deliverOne()).isTrue();

        // webhook 주소는 별도 조회 없이 settings.slackWebhookUrl()에서 그대로 나온다
        assertThat(slackNotifier.deliveredTo).containsExactly(WEBHOOK_URL + "#42");
        assertThat(writeBack.successes).containsExactly("42:claim-token-42");
        assertThat(writeBack.failures).isEmpty();
    }

    @Test
    void aDeliveryFailureIsRoutedToOnFailureAndTheWarnLogCarriesTheIncrementedAttempt() {
        RecordingWriteBack writeBack = new RecordingWriteBack(OptionalInt.of(3));
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                failingSlackNotifier(new IllegalStateException("slack 5xx")),
                writeBack);
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            assertThat(scheduler.deliverOne()).isTrue();    // 실패해도 "일감이 있었다" — 다음 바퀴 간격은 pollInterval 그대로다
        } finally {
            detachListAppender(capturedLogs);
        }

        assertThat(writeBack.failures).containsExactly("42:claim-token-42");
        assertThat(writeBack.successes).isEmpty();
        // 로그에 반드시 실어야 하는 필드들이다 — attempt는 기록(onFailure)이 돌려준, 이미 올려진 횟수다(기록이 로그보다 먼저라 가능하다)
        assertThat(messagesAtLevel(capturedLogs, Level.WARN)).singleElement().asString()
                .contains("pipeline=42", "status=DONE", "attempt=3", "sink=slack",
                        "resp_class=IllegalStateException");
    }

    @Test
    void aStaleNoOpFailureWriteBackIsLoggedWithoutAFabricatedAttemptCount() {
        RecordingWriteBack writeBack = new RecordingWriteBack(OptionalInt.empty());   // 기록이 무효 처리된(다른 서버가 이미 처리한) 상황 모사
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                failingSlackNotifier(new IllegalStateException("slack 5xx")),
                writeBack);
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            assertThat(scheduler.deliverOne()).isTrue();
        } finally {
            detachListAppender(capturedLogs);
        }

        assertThat(messagesAtLevel(capturedLogs, Level.WARN)).singleElement().asString()
                .contains("attempt=stale-no-op");
    }

    @Test
    void aSuccessWriteBackFailureIsNotCountedAsADeliveryFailure() {
        List<String> failures = new ArrayList<>();
        NotifyWriteBack writeBack = new NotifyWriteBack(null, null, null) {
            @Override public void onSuccess(long pipelineId, String token) {
                throw new IllegalStateException("db down during write-back");
            }

            @Override public OptionalInt onFailure(long pipelineId, String token) {
                failures.add(pipelineId + ":" + token);
                return OptionalInt.of(1);
            }
        };
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                recordingSlackNotifier(), writeBack);

        // 전달 자체는 성공했다 — 결과 기록(onSuccess)의 실패는 시도 횟수를 올리지 않고 runSweep까지 올라가
        // 루프 유지용 WARN으로 남는다. 점유가 남았다가 만료되면 다른 서버가 다시 집어 재전달한다
        // (드물게 중복 전달을 감수한다 — 시도 횟수는 "전달을 호출했는데 실패했다"일 때만 올린다).
        assertThatThrownBy(scheduler::deliverOne).isInstanceOf(IllegalStateException.class);
        assertThat(failures).isEmpty();
    }

    @Test
    void anEmptyClaimIsAnIdleSweep() {
        NotifyScheduler scheduler = scheduler(claimerYielding(), recordingSlackNotifier(),
                new RecordingWriteBack());

        assertThat(scheduler.deliverOne()).isFalse();
    }

    @Test
    void theGiveUpAlertPollsOncePerIntervalAndRaisesAnErrorLog() {
        AtomicInteger givenUpQueryCount = new AtomicInteger();
        NotifyScheduler scheduler = scheduler(claimerYielding(), recordingSlackNotifier(),
                new RecordingWriteBack(), givenUpCountingRepository(3, givenUpQueryCount));
        scheduler.stop();   // 루프를 먼저 종료해 두면 runSweep이 다음 바퀴를 예약하지 않아 직접 호출 결과가 항상 같다
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            scheduler.runSweep();   // 생성 시 폴링 시각 = now라서 첫 바퀴가 즉시 1회 폴링한다
            scheduler.runSweep();   // 폴링 시각이 앞으로 밀렸고 주기가 아직 안 됐다 → countGivenUp을 다시 조회하지 않는다
        } finally {
            detachListAppender(capturedLogs);
        }

        assertThat(givenUpQueryCount.get()).isEqualTo(1);
        assertThat(messagesAtLevel(capturedLogs, Level.ERROR)).singleElement().asString()
                .contains("give-up", "count=3");
    }

    @Test
    void aZeroGiveUpBacklogPollsWithoutRaisingAnErrorLog() {
        AtomicInteger givenUpQueryCount = new AtomicInteger();
        NotifyScheduler scheduler = scheduler(claimerYielding(), recordingSlackNotifier(),
                new RecordingWriteBack(), givenUpCountingRepository(0, givenUpQueryCount));
        scheduler.stop();
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            scheduler.runSweep();
        } finally {
            detachListAppender(capturedLogs);
        }

        assertThat(givenUpQueryCount.get()).isEqualTo(1);
        assertThat(messagesAtLevel(capturedLogs, Level.ERROR)).isEmpty();
    }

    @Test
    void emptySweepsGrowGeometricallyCapAtMaxIdleSleepAndResetOnWork() {
        NotifyScheduler scheduler = scheduler(claimerYielding(), recordingSlackNotifier(),
                new RecordingWriteBack());

        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(2));   // base 1초 × 2
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(4));
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(8));   // maxIdleSleep 상한에서 멈춘다
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(8));
        assertThat(scheduler.nextDelay(true)).isEqualTo(Duration.ofSeconds(2));    // 일감이 있었다 → pollInterval로 돌아가고 대기 시간 초기화
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(2));   // base부터 다시 늘어난다
    }

    private static NotifySettings settings() {
        return NotifySettings.builder()
                .enabled(true)
                .pollInterval(Duration.ofSeconds(2))
                .maxIdleSleep(Duration.ofSeconds(8))
                .backoffBase(Duration.ofSeconds(1))
                .backoffMax(Duration.ofMinutes(10))
                .jitterRatio(0.0)
                .leaseDuration(Duration.ofMinutes(1))
                .callTimeout(Duration.ofSeconds(10))
                .maxAttempts(8)
                .schedulerInitialDelay(Duration.ofSeconds(10))
                .slackWebhookUrl(WEBHOOK_URL)
                .enabledAfter(NOW)
                .build();
    }

    /** 알림 기능이 꺼진 설정. enabled=false면 webhook과 enabledAfter를 생략해도 유효하다(둘은 켜진 배포에서만 필수다). */
    private static NotifySettings disabledSettings() {
        return NotifySettings.builder()
                .enabled(false)
                .pollInterval(Duration.ofSeconds(2))
                .maxIdleSleep(Duration.ofSeconds(8))
                .backoffBase(Duration.ofSeconds(1))
                .backoffMax(Duration.ofMinutes(10))
                .jitterRatio(0.0)
                .leaseDuration(Duration.ofMinutes(1))
                .callTimeout(Duration.ofSeconds(10))
                .maxAttempts(8)
                .schedulerInitialDelay(Duration.ofSeconds(10))
                .build();
    }

    private NotifyScheduler scheduler(NotifyClaimer claimer, SlackNotifier slackNotifier,
            NotifyWriteBack writeBack) {
        return scheduler(claimer, slackNotifier, writeBack, null);
    }

    private NotifyScheduler scheduler(NotifyClaimer claimer, SlackNotifier slackNotifier,
            NotifyWriteBack writeBack, NotifyRepository notifyRepository) {
        return new NotifyScheduler(claimer, slackNotifier, writeBack, notifyRepository, settings(), CLOCK);
    }

    private static NotifyClaimer claimerYielding(NotifyClaim... claims) {
        Deque<NotifyClaim> queue = new ArrayDeque<>(List.of(claims));
        return new NotifyClaimer(null, null, null, null) {
            @Override public Optional<NotifyClaim> claimOne() {
                return Optional.ofNullable(queue.poll());
            }
        };
    }

    private static NotifyClaimer claimerNeverCalled() {
        return new NotifyClaimer(null, null, null, null) {
            @Override public Optional<NotifyClaim> claimOne() {
                throw new AssertionError("loop가 기동되지 않는 시나리오에서 claim을 시도하면 안 된다");
            }
        };
    }

    private static NotifyPayload payload(long pipelineId) {
        return NotifyPayload.builder()
                .pipelineId(pipelineId).type("INSTALL").terminalStatus("DONE")
                .targetRef("ts-" + pipelineId).schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();
    }

    private static RecordingSlackNotifier recordingSlackNotifier() {
        return new RecordingSlackNotifier(null);
    }

    private static RecordingSlackNotifier failingSlackNotifier(RuntimeException failure) {
        return new RecordingSlackNotifier(failure);
    }

    /**
     * countGivenUp만 응답하는 fake {@code NotifyRepository}다. 인터페이스(JpaRepository 상속)라서
     * anonymous 서브클래스 대신 JDK Proxy로 만든다. 그 외 메서드가 불리면 테스트 결함이므로 즉시 실패시킨다.
     */
    private static NotifyRepository givenUpCountingRepository(long givenUpCount, AtomicInteger queryCount) {
        return (NotifyRepository) Proxy.newProxyInstance(
                NotifyRepository.class.getClassLoader(),
                new Class<?>[] {NotifyRepository.class},
                (proxy, method, methodArguments) -> {
                    if ("countGivenUp".equals(method.getName())) {
                        queryCount.incrementAndGet();
                        return givenUpCount;
                    }
                    throw new AssertionError("unexpected NotifyRepository call: " + method.getName());
                });
    }

    /** 스케줄러 로그를 붙잡는 Logback ListAppender. WARN/ERROR 로그의 구조화 필드를 단언하는 데 쓴다. */
    private static ListAppender<ILoggingEvent> attachListAppender() {
        Logger schedulerLogger = (Logger) LoggerFactory.getLogger(NotifyScheduler.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        schedulerLogger.addAppender(listAppender);
        return listAppender;
    }

    private static void detachListAppender(ListAppender<ILoggingEvent> listAppender) {
        ((Logger) LoggerFactory.getLogger(NotifyScheduler.class)).detachAppender(listAppender);
    }

    private static List<String> messagesAtLevel(ListAppender<ILoggingEvent> listAppender, Level level) {
        return listAppender.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 전달 호출을 기록하거나(성공 모사) 지정된 예외를 던지는(실패 모사) 스텁 전송처. */
    private static final class RecordingSlackNotifier extends SlackNotifier {
        private final List<String> deliveredTo = new ArrayList<>();
        private final RuntimeException failure;

        private RecordingSlackNotifier(RuntimeException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public void deliver(String webhookUrl, NotifyPayload payload) {
            if (failure != null) {
                throw failure;
            }
            deliveredTo.add(webhookUrl + "#" + payload.pipelineId());
        }
    }

    /** 결과 기록 호출만 남기는 스텁. 어느 경로(onSuccess/onFailure)로 어떤 점유가 닫혔는지 기록한다. */
    private static final class RecordingWriteBack extends NotifyWriteBack {
        private final List<String> successes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final OptionalInt failureAttempts;

        private RecordingWriteBack() {
            this(OptionalInt.of(1));
        }

        private RecordingWriteBack(OptionalInt failureAttempts) {
            super(null, null, null);
            this.failureAttempts = failureAttempts;
        }

        @Override
        public void onSuccess(long pipelineId, String token) {
            successes.add(pipelineId + ":" + token);
        }

        @Override
        public OptionalInt onFailure(long pipelineId, String token) {
            failures.add(pipelineId + ":" + token);
            return failureAttempts;
        }
    }
}
