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
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * {@link TerminalNotifier}가 실제 DB 위에서 알림 한 건을 트랜잭션 하나로 처리하는 흐름을 검증한다.
 * 테스트의 래핑 트랜잭션을 꺼서 처리 한 건 한 건이 실제로 커밋되게 한다. Slack 전송만
 * 가짜 전송처(받은 payload를 기록하거나 지정된 예외를 던진다)로 바꾸고 나머지는 전부 실물이다.
 *
 * 검증하는 것:
 * - 조회 조건: 끝난 상태(DONE, FAILED, CANCELLED)이고, 아직 알림이 안 나갔고, 도입 시각 이후에 끝났고
 *   (경계 포함), 시도 횟수가 상한 아래이고, 재시도 시각이 없거나 지난 행만 잡힌다.
 * - 순서: 갓 끝난 행(재시도 예약 없음)이 재시도 대기 행보다 먼저, 같으면 id 순서다.
 * - 성공: 완료 시각을 적고 재시도 예약을 지우며, 같은 행이 다시 나가지 않는다.
 * - 실패: 시도 횟수가 쌓이고 재시도 간격이 횟수에 비례해 늘어난다. 상한에 닿으면 자동 재시도를 멈추고
 *   ERROR 로그를 남긴다. 실패 기록은 전달 예외에도 롤백되지 않고 커밋된다.
 * - FAILED payload: 순서가 가장 앞선 FAILED task에서 실패 단계와 에러 코드를 채운다.
 * - 멈춘 건 경보: 재시도가 멈춘 행이 남아 있는 동안 경보 주기마다 한 번씩 ERROR 로그를 낸다.
 * - 켜고 끄기: 꺼진 설정이면 start()가 루프를 올리지 않는다.
 * 자세한 배경은 ADR-022 참조.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TerminalNotifierTest {

    private static final Instant START = Instant.parse("2026-07-09T00:00:00Z");
    /** 알림 도입 시각. 기본 savePipeline 행(lastActivityAt = START-1분)이 전부 이 값 이후가 되도록 잡은 고정값이다. */
    private static final Instant ENABLED_AFTER = START.minus(Duration.ofDays(1));
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";
    private static final NotifySettings SETTINGS = new NotifySettings(true, WEBHOOK_URL, ENABLED_AFTER);

    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final MutableClock clock = new MutableClock(START);

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void aSweepDrainsEveryTerminalUnnotifiedRowAndSkipsLiveOrAlreadyNotifiedRows() {
        Pipeline done = savePipeline("nt-done", PipelineStatus.DONE);
        Pipeline failed = savePipeline("nt-failed", PipelineStatus.FAILED);
        Pipeline cancelled = savePipeline("nt-cancelled", PipelineStatus.CANCELLED);
        savePipeline("nt-running", PipelineStatus.RUNNING);     // 아직 끝나지 않은 행은 알림 대상이 아니다
        savePipeline("nt-pending", PipelineStatus.PENDING);
        savePipeline("nt-notified", PipelineStatus.DONE,
                builder -> builder.notifiedAt(START.minusSeconds(5)));   // 이미 나간 행도 대상이 아니다

        RecordingSlackNotifier slack = deliveringSlack();
        TerminalNotifier notifier = notifier(slack);
        notifier.runSweep();   // 한 바퀴가 밀린 대상 전부를 배수한다

        assertThat(slack.deliveredPipelineIds())
                .containsExactly(done.getId(), failed.getId(), cancelled.getId());
        assertThat(reload(done).getNotifiedAt()).isEqualTo(START);
        assertThat(notifier.deliverOne()).isFalse();   // 나간 행이 다시 잡히지 않는다
    }

    @Test
    void aRowFinishedExactlyAtTheAdoptionCutoffIsNotifiedButOneSecondEarlierIsNot() {
        Pipeline atCutoff = savePipeline("nt-at-cutoff", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER));   // 경계 포함 — 정확히 도입 시각에 끝난 행부터 대상이다
        savePipeline("nt-before-cutoff", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER.minusSeconds(1)));

        RecordingSlackNotifier slack = deliveringSlack();
        TerminalNotifier notifier = notifier(slack);

        assertThat(notifier.deliverOne()).isTrue();
        assertThat(notifier.deliverOne()).isFalse();   // 도입 시각 전에 끝난 옛 행은 절대 나가지 않는다
        assertThat(slack.deliveredPipelineIds()).containsExactly(atCutoff.getId());
    }

    @Test
    void rowsWaitingForTheirRetryTimeOrPastTheAttemptCapAreNotPicked() {
        savePipeline("nt-given-up", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(TerminalNotifier.MAX_ATTEMPTS));
        Pipeline waiting = savePipeline("nt-retry-waiting", PipelineStatus.DONE,
                builder -> builder.notifyNextAt(START.plusSeconds(30)));

        RecordingSlackNotifier slack = deliveringSlack();
        TerminalNotifier notifier = notifier(slack);
        assertThat(notifier.deliverOne()).isFalse();   // 재시도 시각 전 + 시도 상한 도달 → 아무 행도 잡히지 않는다

        clock.advance(Duration.ofSeconds(30));
        assertThat(notifier.deliverOne()).isTrue();    // 재시도 시각이 오면 다시 대상이 된다
        assertThat(slack.deliveredPipelineIds()).containsExactly(waiting.getId());
        assertThat(notifier.deliverOne()).isFalse();   // 상한에 닿은 행은 시간이 지나도 잡히지 않는다
    }

    @Test
    void freshRowsGoBeforeDueRetriesAndEqualRowsGoInIdOrder() {
        Pipeline retried = savePipeline("nt-retried", PipelineStatus.DONE,
                builder -> builder.notifyNextAt(START.minusSeconds(10)));   // 재시도 시각이 이미 지난 재시도 대기 행
        Pipeline freshLowId = savePipeline("nt-fresh-a", PipelineStatus.DONE);
        Pipeline freshHighId = savePipeline("nt-fresh-b", PipelineStatus.DONE);

        RecordingSlackNotifier slack = deliveringSlack();
        notifier(slack).runSweep();

        // 갓 끝난 행(재시도 예약 없음)이 먼저, 같으면 id 순서 — 항상 같은 순서다
        assertThat(slack.deliveredPipelineIds())
                .containsExactly(freshLowId.getId(), freshHighId.getId(), retried.getId());
    }

    @Test
    void aSuccessfulDeliveryStampsNotifiedAtClearsTheRetryScheduleAndNeverResends() {
        Pipeline done = savePipeline("nt-success", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(1).notifyNextAt(START.minusSeconds(10)));   // 재시도를 기다리던 행

        RecordingSlackNotifier slack = deliveringSlack();
        TerminalNotifier notifier = notifier(slack);
        assertThat(notifier.deliverOne()).isTrue();

        Pipeline after = reload(done);
        assertThat(after.getNotifiedAt()).isEqualTo(START);
        assertThat(after.getNotifyNextAt()).isNull();   // 남아 있던 재시도 예약도 지운다

        clock.advance(Duration.ofHours(1));
        assertThat(notifier.deliverOne()).isFalse();    // 알림이 나간 행은 영구히 제외된다
        assertThat(slack.deliveredPipelineIds()).containsExactly(done.getId());
    }

    @Test
    void deliveryFailuresGrowTheRetryDelayLinearlyThenGiveUpWithAnErrorLog() {
        Pipeline done = savePipeline("nt-failing", PipelineStatus.DONE);
        TerminalNotifier notifier = notifier(failingSlack(new RestClientException("slack delivery failed: http 500")));

        assertThat(notifier.deliverOne()).isTrue();     // 1번째 실패 — 실패해도 "일감이 있었다"다
        Pipeline after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(1);
        assertThat(after.getNotifyNextAt()).isEqualTo(START.plus(TerminalNotifier.RETRY_DELAY_STEP));
        assertThat(notifier.deliverOne()).isFalse();    // 재시도 시각 전에는 같은 행을 다시 잡지 않는다

        clock.advance(TerminalNotifier.RETRY_DELAY_STEP);
        assertThat(notifier.deliverOne()).isTrue();     // 2번째 실패 — 간격이 횟수에 비례해 늘어난다
        after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(2);
        assertThat(after.getNotifyNextAt())
                .isEqualTo(clock.instant().plus(TerminalNotifier.RETRY_DELAY_STEP.multipliedBy(2)));

        clock.advance(TerminalNotifier.RETRY_DELAY_STEP.multipliedBy(2));
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            assertThat(notifier.deliverOne()).isTrue();   // 상한 번째 실패 → 자동 재시도 중단
        } finally {
            detachListAppender(capturedLogs);
        }
        after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(TerminalNotifier.MAX_ATTEMPTS);
        assertThat(after.getNotifyNextAt()).isNull();   // 재시도가 멈추므로 재시도 시각을 비운다
        assertThat(messagesAtLevel(capturedLogs, Level.ERROR)).singleElement().asString()
                .contains("give-up", "after " + TerminalNotifier.MAX_ATTEMPTS + " attempts");

        clock.advance(Duration.ofHours(1));
        assertThat(notifier.deliverOne()).isFalse();    // 시도 횟수 조건이 재선택을 막는다
        assertThat(pipelineRepository.countGivenUp(TerminalNotifier.MAX_ATTEMPTS)).isEqualTo(1);
    }

    @Test
    void aDeliveryFailureRecordIsCommittedNotRolledBack() {
        Pipeline done = savePipeline("nt-committed-failure", PipelineStatus.DONE);
        notifier(failingSlack(new RestClientException("slack delivery failed: http 500"))).deliverOne();

        // 전달 실패 예외는 처리 트랜잭션 안에서 잡히므로 실패 기록이 그 트랜잭션과 함께 커밋된다.
        // 완전히 새 트랜잭션에서 DB를 처음부터 다시 읽어 커밋된 값임을 확인한다.
        Pipeline reread = new TransactionTemplate(transactionManager)
                .execute(transactionStatus -> pipelineRepository.findById(done.getId()).orElseThrow());
        assertThat(reread.getNotifyAttempts()).isEqualTo(1);
        assertThat(reread.getNotifiedAt()).isNull();
    }

    @Test
    void aNonDeliveryExceptionEscalatesWithoutBurningAnAttempt() {
        // 전달 실패(RestClientException)만 재시도로 수렴시킨다. 그 외 예외는 버그라서 잡지 않고
        // 트랜잭션을 롤백시켜 올린다 — 시도 횟수를 소모하며 조용히 give-up으로 흘러가면 안 된다.
        Pipeline done = savePipeline("nt-bug-escalates", PipelineStatus.DONE);
        TerminalNotifier notifier = notifier(failingSlack(new IllegalStateException("전달 코드의 버그")));

        assertThatThrownBy(notifier::deliverOne).isInstanceOf(IllegalStateException.class);

        Pipeline reread = reload(done);
        assertThat(reread.getNotifyAttempts()).isZero();
        assertThat(reread.getNotifiedAt()).isNull();
    }

    @Test
    void aFailedPipelinePayloadNamesTheEarliestFailedStepAndADonePayloadCarriesNeither() {
        Pipeline failed = savePipeline("nt-failed-steps", PipelineStatus.FAILED);
        saveTask(failed.getId(), 0, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.DONE, null);
        saveTask(failed.getId(), 1, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.FAILED, ErrorCode.JOB_FAILED);
        saveTask(failed.getId(), 2, TaskDefinition.NETWORK_READY_V1, TaskStatus.FAILED, ErrorCode.CHECK_ERROR);
        Pipeline done = savePipeline("nt-done-clean", PipelineStatus.DONE);
        saveTask(done.getId(), 0, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.DONE, null);

        RecordingSlackNotifier slack = deliveringSlack();
        notifier(slack).runSweep();

        NotifyPayload failedPayload = slack.deliveredPayloads.getFirst();
        // 실패 단계 이름은 taskDefinition(레시피 단계의 상수 이름)에서, 순서가 가장 앞선 FAILED task 기준으로 나온다
        assertThat(failedPayload.failedTask()).isEqualTo(TaskDefinition.AWS_SERVICE_APPLY_V1.name());
        assertThat(failedPayload.errorCode()).isEqualTo(ErrorCode.JOB_FAILED.name());
        assertThat(failedPayload.terminalStatus()).isEqualTo(PipelineStatus.FAILED.name());
        NotifyPayload donePayload = slack.deliveredPayloads.getLast();
        assertThat(donePayload.failedTask()).isNull();   // FAILED가 아니면 실패 단계와 에러 코드를 싣지 않는다
        assertThat(donePayload.errorCode()).isNull();
    }

    @Test
    void aLegacyFailedTaskWithoutADefinitionFallsBackToTheTaskName() {
        Pipeline failed = savePipeline("nt-failed-legacy", PipelineStatus.FAILED);
        taskRepository.save(Task.builder()   // taskDefinition이 없는 옛 행을 흉내 낸다
                .pipelineId(failed.getId())
                .sequence(0)
                .taskName(TaskOperation.AWS_SERVICE_TF_APPLY.mechanism())
                .operation(TaskOperation.AWS_SERVICE_TF_APPLY)
                .status(TaskStatus.FAILED)
                .errorCode(ErrorCode.JOB_FAILED)
                .failCount(0)
                .build());

        RecordingSlackNotifier slack = deliveringSlack();
        notifier(slack).deliverOne();

        // 대체 값(taskName)도 enum에서 나온 정해진 이름이라 민감 정보 규칙을 지킨다
        assertThat(slack.deliveredPayloads.getFirst().failedTask())
                .isEqualTo(TaskOperation.Mechanism.TERRAFORM_JOB);
    }

    @Test
    void theGiveUpBacklogAlertFiresOncePerAlertIntervalWhileTheBacklogRemains() {
        savePipeline("nt-given-up-backlog", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(TerminalNotifier.MAX_ATTEMPTS));

        TerminalNotifier notifier = notifier(deliveringSlack());
        ListAppender<ILoggingEvent> capturedLogs = attachListAppender();
        try {
            notifier.runSweep();   // 생성 시 경보 폴링 시각 = 지금이라 첫 바퀴가 즉시 한 번 경보한다
            notifier.runSweep();   // 경보 주기가 아직 안 지났다 → 다시 경보하지 않는다
            assertThat(messagesAtLevel(capturedLogs, Level.ERROR)).singleElement().asString()
                    .contains("give-up backlog", "count=1");

            clock.advance(TerminalNotifier.GIVE_UP_ALERT_POLL_INTERVAL);
            notifier.runSweep();   // 주기가 지났고 멈춘 건이 남아 있다 → 다시 경보한다
            assertThat(messagesAtLevel(capturedLogs, Level.ERROR)).hasSize(2);
        } finally {
            detachListAppender(capturedLogs);
        }
    }

    @Test
    void aDisabledSwitchMakesStartANoOpThatSchedulesNothing() {
        TerminalNotifier notifier = new TerminalNotifier(null, null, null,
                new NotifySettings(false, null, null), null, clock);

        // 이미 종료된 executor에 예약하면 RejectedExecutionException이 난다. 꺼져 있으면 enabled 확인이
        // 예약 전에 조용히 돌아와야 하므로 stop() 뒤의 start()가 아무 예외도 내지 않아야 한다.
        // 아래 켜진 케이스가 대조군이다.
        notifier.stop();
        assertThatCode(notifier::start).doesNotThrowAnyException();
    }

    @Test
    void anEnabledSwitchSchedulesTheLoop() {
        TerminalNotifier notifier = new TerminalNotifier(null, null, null, SETTINGS, null, clock);

        notifier.stop();
        assertThatThrownBy(notifier::start).isInstanceOf(RejectedExecutionException.class);
    }

    /** 검증 대상을 만든다. 트랜잭션 경계는 실물(주입받은 트랜잭션 매니저)이고 전송처만 가짜다. */
    private TerminalNotifier notifier(SlackNotifier slackNotifier) {
        return new TerminalNotifier(pipelineRepository, taskRepository, slackNotifier, SETTINGS,
                new TransactionTemplate(transactionManager), clock);
    }

    private Pipeline savePipeline(String target, PipelineStatus status) {
        return savePipeline(target, status, builder -> { });
    }

    private Pipeline savePipeline(String target, PipelineStatus status,
            Consumer<Pipeline.PipelineBuilder> customize) {
        Pipeline.PipelineBuilder builder = Pipeline.builder()
                .type(PipelineType.INSTALL)
                .target(target)
                .status(status)
                .createdAt(START.minus(Duration.ofMinutes(10)))
                .lastActivityAt(START.minus(Duration.ofMinutes(1)))
                .nextDueAt(START)
                .cancelRequested(false);
        customize.accept(builder);
        return pipelineRepository.save(builder.build());
    }

    private void saveTask(Long pipelineId, int sequence, TaskDefinition definition, TaskStatus status,
            ErrorCode errorCode) {
        taskRepository.save(Task.builder()
                .pipelineId(pipelineId)
                .sequence(sequence)
                .taskName(definition.mechanism())      // PipelineInserter와 같은 방식: taskName에는 실행 방식(mechanism) 값이 저장된다
                .operation(definition.operation())
                .taskDefinition(definition.name())     // 레시피 단계 이름의 기준 값 — failed_task의 1순위 원천
                .status(status)
                .errorCode(errorCode)
                .failCount(0)
                .build());
    }

    private Pipeline reload(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow();
    }

    private static RecordingSlackNotifier deliveringSlack() {
        return new RecordingSlackNotifier(null);
    }

    private static RecordingSlackNotifier failingSlack(RuntimeException failure) {
        return new RecordingSlackNotifier(failure);
    }

    /** TerminalNotifier 로그를 붙잡는 Logback ListAppender. give-up ERROR 로그와 경보 반복을 단언하는 데 쓴다. */
    private static ListAppender<ILoggingEvent> attachListAppender() {
        Logger notifierLogger = (Logger) LoggerFactory.getLogger(TerminalNotifier.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        notifierLogger.addAppender(listAppender);
        return listAppender;
    }

    private static void detachListAppender(ListAppender<ILoggingEvent> listAppender) {
        ((Logger) LoggerFactory.getLogger(TerminalNotifier.class)).detachAppender(listAppender);
    }

    private static List<String> messagesAtLevel(ListAppender<ILoggingEvent> listAppender, Level level) {
        return listAppender.list.stream()
                .filter(loggingEvent -> loggingEvent.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 받은 payload를 기록하거나(성공 모사) 지정된 예외를 던지는(실패 모사) 가짜 전송처. */
    private static final class RecordingSlackNotifier extends SlackNotifier {
        private final List<NotifyPayload> deliveredPayloads = new ArrayList<>();
        private final RuntimeException failure;

        private RecordingSlackNotifier(RuntimeException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public void deliver(String webhookUrl, NotifyPayload payload) {
            assertThat(webhookUrl).isEqualTo(WEBHOOK_URL);   // 전달 주소는 설정 값이 그대로 온다
            if (failure != null) {
                throw failure;
            }
            deliveredPayloads.add(payload);
        }

        private List<Long> deliveredPipelineIds() {
            return deliveredPayloads.stream().map(NotifyPayload::pipelineId).toList();
        }
    }
}
