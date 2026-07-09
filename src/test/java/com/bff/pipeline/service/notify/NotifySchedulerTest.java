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
 * {@link NotifyScheduler}의 순수 로직 단위 테스트다({@code PipelineSchedulerTest} 스타일 — 스케줄러 스레드
 * 기동 없이 {@code deliverOne}/{@code nextDelay}/{@code runSweep}을 직접 호출한다). 채널 gate(=start의
 * enabled 가드 — disabled면 loop 자체를 올리지 않는다; 오너 결정 2026-07-09의 env 채널), 전달 webhook이
 * 설정({@code settings.slackWebhookUrl()})에서 나오는 것, 성공/실패의 write-back 라우팅(실패는 sweep을
 * 죽이지 않고 backoff 기록으로 흡수), 실패 WARN의 구조화 필드(attempt = write-back 반환값, fencing no-op면
 * stale-no-op — 구현 명세 §7 MUST 필드), give-up 경보 폴링(주기당 1회 countGivenUp 조회 + 0 초과면 ERROR
 * 로그 — Logback ListAppender로 캡처 단언), 빈 sweep의 geometric idle backoff와 일감 발견 시 리셋을
 * 검증한다. 협력자는 이 repo 규약대로 fake로 대체한다 — NotifyRepository는 인터페이스라 countGivenUp만
 * 응답하는 JDK Proxy fake를 쓴다.
 */
class NotifySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";

    @Test
    void aDisabledMasterSwitchNeverSchedulesTheLoop() {
        NotifyScheduler scheduler = new NotifyScheduler(claimerNeverCalled(), recordingSlackNotifier(),
                new RecordingWriteBack(), null, disabledSettings(), CLOCK);

        // 종료된 executor에 schedule하면 RejectedExecutionException이 난다 — enabled 가드가 그 전에
        // 끊어야(loop 미기동 = 채널 gate) start가 조용히 돌아온다. 아래 enabled 케이스가 대조군이다.
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

        // webhook은 채널 조회 없이 settings.slackWebhookUrl()에서 그대로 나온다
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
            assertThat(scheduler.deliverOne()).isTrue();    // 실패해도 "일감이 있었다" — 케이던스는 pollInterval 유지
        } finally {
            detachListAppender(capturedLogs);
        }

        assertThat(writeBack.failures).containsExactly("42:claim-token-42");
        assertThat(writeBack.successes).isEmpty();
        // 구현 명세 §7 MUST 필드 — attempt는 write-back의 post-increment 반환값이다(write-back이 로그보다 먼저)
        assertThat(messagesAtLevel(capturedLogs, Level.WARN)).singleElement().asString()
                .contains("pipeline=42", "status=DONE", "attempt=3", "sink=slack",
                        "resp_class=IllegalStateException");
    }

    @Test
    void aStaleNoOpFailureWriteBackIsLoggedWithoutAFabricatedAttemptCount() {
        RecordingWriteBack writeBack = new RecordingWriteBack(OptionalInt.empty());   // fencing no-op 모사
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

        // 전달은 성공했다 — write-back 실패는 attempts를 태우지 않고 runSweep의 loop-survival WARN으로 전파된다
        // (lease가 남아 만료 후 재claim → at-least-once 중복 수용, ADR-022 §2 "전달 실패" 한정)
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
        scheduler.stop();   // 종료된 loop → runSweep이 후속 sweep을 재예약하지 않아 직접 호출이 결정적이다
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            scheduler.runSweep();   // 생성 시 폴링 시각 = now → 첫 sweep이 즉시 1회 폴링한다
            scheduler.runSweep();   // 폴링 시각이 전진했고 주기 미도래 → countGivenUp 재조회 없음
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

        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(2));   // base 1s × 2
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(4));
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(8));   // maxIdleSleep 상한
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(8));
        assertThat(scheduler.nextDelay(true)).isEqualTo(Duration.ofSeconds(2));    // 일감 → pollInterval + 리셋
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(2));   // base부터 다시 성장
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

    /** master switch off — enabled=false면 webhook/enabledAfter 없이도 유효하다(NotifySettings 조건부 필수). */
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
     * countGivenUp만 응답하는 fake {@code NotifyRepository} — 인터페이스(JpaRepository 상속)라 anonymous
     * 서브클래스 대신 JDK Proxy로 만든다. 그 외 메서드 호출은 테스트 결함이므로 즉시 실패시킨다.
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

    /** 스케줄러 로그를 캡처하는 Logback ListAppender — 구조화 WARN/ERROR 필드를 단언하는 데 쓴다. */
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

    /** 전달 호출을 기록하거나(성공 모사) 지정된 예외를 던지는(실패 모사) 스텁 sink. */
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

    /** write-back 라우팅만 기록하는 스텁 — 어느 경로(onSuccess/onFailure)로 어떤 claim이 닫혔는지 남긴다. */
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
