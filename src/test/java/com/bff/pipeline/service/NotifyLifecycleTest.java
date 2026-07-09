package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.bff.pipeline.model.NotifyClaim;
import com.bff.pipeline.repository.NotifyRepository;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.notify.NotifyClaimer;
import com.bff.pipeline.service.notify.NotifyWriteBack;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림의 점유(claim)와 결과 기록(write-back)이 DB 위에서 실제로 어떻게 도는지 검증한다.
 * {@code PipelineSoftCapTest} 패턴을 따라 테스트의 래핑 트랜잭션을 꺼서,
 * 점유 트랜잭션과 기록 트랜잭션이 실제로 따로 커밋되게 한다.
 *
 * 검증하는 것:
 * - 점유 조건: 끝난 상태이고, 미알림이고, 재시도 시각이 지났고, 다른 점유가 없고,
 *   자동 재시도를 중단한 행은 배제하고, 도입 시각 이후여야 잡힌다.
 * - 순서: notify_next_at이 NULL인(갓 끝난) 행이 먼저, 같으면 id 순 — 항상 같은 순서다.
 * - FAILED payload: sequence가 가장 앞선 FAILED task에서 채운다. taskDefinition을 우선 쓰고,
 *   그 값이 없는 옛 행은 taskName으로 대신한다.
 * - 토큰 확인: 토큰이 맞지 않으면 성공/실패 기록 모두 아무것도 바꾸지 않는다(실패 쪽은 empty를 돌려준다).
 * - 재시도: 실패할수록 간격이 두 배씩 늘고, 상한 도달 시 자동 재시도를 중단하며 ERROR 로그를 남긴다.
 * - 격리: 알림 점유가 실행의 동시 실행 카운트를 부풀리지 않는다.
 * - 최소 한 번 전달: 점유 만료 후 다시 점유되면 드물게 중복 전달이 생길 수 있고, 이를 감수한다.
 * - 도입 시각 컷오프(설정 {@code enabledAfter}): 그 전에 끝난 옛 행은 절대 잡히지 않는다 —
 *   알림을 처음 켜는 순간 옛 파이프라인 알림이 한꺼번에 쏟아지는 것을 막는 조건이다.
 * 기존 시나리오 행들의 lastActivityAt은 전부 컷오프({@code ENABLED_AFTER}) 이후라 컷오프의 영향을 받지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotifyClaimer.class, NotifyWriteBack.class, NotifyLifecycleTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotifyLifecycleTest {

    private static final Instant START = Instant.parse("2026-07-09T00:00:00Z");
    /** 알림 도입 시각 컷오프. 기본 savePipeline 행(lastActivityAt = START-1분)이 전부 이 값 이후가 되도록 잡은 고정값이다. */
    private static final Instant ENABLED_AFTER = START.minus(Duration.ofDays(1));
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(5);
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";

    @Autowired private NotifyClaimer claimer;
    @Autowired private NotifyWriteBack writeBack;
    @Autowired private NotifyRepository notifyRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private MutableClock clock;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        clock.set(START);
    }

    @Test
    void claimTakesOnlyTerminalUnnotifiedRowsAndStampsTheNotifyLease() {
        Pipeline done = savePipeline("nt-done", PipelineStatus.DONE);
        Pipeline cancelled = savePipeline("nt-cancelled", PipelineStatus.CANCELLED);
        savePipeline("nt-running", PipelineStatus.RUNNING);   // 아직 끝나지 않은 상태 → 알림 대상이 아니다

        NotifyClaim first = claimer.claimOne().orElseThrow();
        NotifyClaim second = claimer.claimOne().orElseThrow();

        assertThat(first.pipelineId()).isEqualTo(done.getId());       // 둘 다 notify_next_at이 NULL이라 id 순서로 잡힌다
        assertThat(second.pipelineId()).isEqualTo(cancelled.getId());
        assertThat(claimer.claimOne()).isEmpty();                     // RUNNING은 절대 잡히지 않는다

        Pipeline claimed = reload(done);
        assertThat(claimed.getNotifyClaimedBy()).isEqualTo(first.token());
        assertThat(claimed.getNotifyClaimedUntil()).isEqualTo(START.plus(LEASE));
        assertThat(first.payload().terminalStatus()).isEqualTo(PipelineStatus.DONE.name());
    }

    @Test
    void claimSkipsNotifiedNotYetDueLeasedAndGivenUpRows() {
        savePipeline("nt-notified", PipelineStatus.DONE,
                builder -> builder.notifiedAt(START.minusSeconds(5)));
        savePipeline("nt-backoff-waiting", PipelineStatus.DONE,
                builder -> builder.notifyNextAt(START.plusSeconds(30)));
        savePipeline("nt-live-lease", PipelineStatus.DONE,
                builder -> builder.notifyClaimedBy("live-claim-token").notifyClaimedUntil(START.plusSeconds(30)));
        // 자동 재시도를 중단한 행은 notify_next_at이 비어 있어도 시도 횟수 조건만으로 배제된다(먼 미래 값에 의존하지 않는다)
        savePipeline("nt-given-up", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(MAX_ATTEMPTS));

        assertThat(claimer.claimOne()).isEmpty();
    }

    @Test
    void freshTerminalsWithNullNotifyNextAtClaimBeforeDueBackoffRows() {
        Pipeline retried = savePipeline("nt-retried", PipelineStatus.DONE,
                builder -> builder.notifyNextAt(START.minusSeconds(10)));   // 재시도 시각이 이미 지난 재시도 대기 행
        Pipeline freshLowId = savePipeline("nt-fresh-a", PipelineStatus.DONE);
        Pipeline freshHighId = savePipeline("nt-fresh-b", PipelineStatus.DONE);

        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(freshLowId.getId());   // NULL(갓 끝난 행)이 먼저
        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(freshHighId.getId());  // 같으면 id 순서
        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(retried.getId());
    }

    @Test
    void aFailedPipelineFillsThePayloadFromTheLowestSequenceFailedTask() {
        Pipeline failed = savePipeline("nt-failed", PipelineStatus.FAILED);
        saveTask(failed.getId(), 0, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.DONE, null);
        saveTask(failed.getId(), 1, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.FAILED, ErrorCode.JOB_FAILED);
        saveTask(failed.getId(), 2, TaskDefinition.NETWORK_READY_V1, TaskStatus.FAILED, ErrorCode.CHECK_ERROR);

        NotifyPayload payload = claimer.claimOne().orElseThrow().payload();

        // failed_task는 taskDefinition(상수 이름)에서 나온다 — taskName은 여러 단계가 같은 값을 공유해 어느 단계인지 못 가린다
        assertThat(payload.failedTask()).isEqualTo(TaskDefinition.AWS_SERVICE_APPLY_V1.name());   // sequence가 가장 앞선 FAILED
        assertThat(payload.errorCode()).isEqualTo(ErrorCode.JOB_FAILED.name());
        assertThat(payload.terminalStatus()).isEqualTo(PipelineStatus.FAILED.name());
        assertThat(payload.type()).isEqualTo(PipelineType.INSTALL.name());
        assertThat(payload.targetRef()).isEqualTo("nt-failed");    // target 키 그대로 — 연결 상세(host 등)는 싣지 않는다
        assertThat(payload.schemaVersion()).isEqualTo(NotifyPayload.SCHEMA_VERSION);

        Set<String> closedTaskKeys = Arrays.stream(TaskDefinition.values())
                .map(TaskDefinition::name)
                .collect(Collectors.toSet());
        assertThat(payload.failedTask()).isIn(closedTaskKeys);     // 정해진 단계 이름 목록 안의 값이다(민감 정보 유출 방지 규칙)
    }

    @Test
    void aLegacyFailedTaskWithoutADefinitionFallsBackToTheMechanismTaskName() {
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

        NotifyPayload payload = claimer.claimOne().orElseThrow().payload();

        // 대체 값(taskName)도 enum에서 나온 정해진 이름이라 민감 정보 규칙을 지킨다
        assertThat(payload.failedTask()).isEqualTo(TaskOperation.Mechanism.TERRAFORM_JOB);
    }

    @Test
    void aNonFailedPipelinePayloadCarriesNoFailedTaskOrErrorCode() {
        Pipeline done = savePipeline("nt-done-clean", PipelineStatus.DONE);
        saveTask(done.getId(), 0, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.DONE, null);

        NotifyPayload payload = claimer.claimOne().orElseThrow().payload();

        assertThat(payload.failedTask()).isNull();
        assertThat(payload.errorCode()).isNull();
    }

    @Test
    void successWriteBackStampsNotifiedAtClearsLeaseAndBackoffAndExcludesTheRow() {
        Pipeline done = savePipeline("nt-success", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(1).notifyNextAt(START.minusSeconds(10)));   // 재시도를 기다리던 행

        NotifyClaim claim = claimer.claimOne().orElseThrow();
        writeBack.onSuccess(claim.pipelineId(), claim.token());

        Pipeline after = reload(done);
        assertThat(after.getNotifiedAt()).isEqualTo(START);
        assertThat(after.getNotifyClaimedBy()).isNull();
        assertThat(after.getNotifyClaimedUntil()).isNull();
        assertThat(after.getNotifyNextAt()).isNull();          // 남아 있던 재시도 예약도 지운다

        clock.advance(Duration.ofHours(1));
        assertThat(claimer.claimOne()).isEmpty();              // 알림이 나간 행은 영구히 제외된다
    }

    @Test
    void aMismatchedTokenWriteBackIsANoOpOnBothPaths() {
        Pipeline done = savePipeline("nt-fenced", PipelineStatus.DONE);
        NotifyClaim claim = claimer.claimOne().orElseThrow();

        writeBack.onSuccess(claim.pipelineId(), "not-the-claim-token");
        // 토큰이 다르면 기록하지 않고 empty를 돌려준다 — 호출자가 attempt=stale-no-op으로 로깅하는 근거
        assertThat(writeBack.onFailure(claim.pipelineId(), "not-the-claim-token")).isEmpty();

        Pipeline after = reload(done);
        assertThat(after.getNotifiedAt()).isNull();
        assertThat(after.getNotifyAttempts()).isZero();
        assertThat(after.getNotifyClaimedBy()).isEqualTo(claim.token());   // 지금의 점유 정보는 그대로 남는다
    }

    @Test
    void writeBackAfterAnotherWorkerAlreadySucceededIsANoOpEvenWithAMatchingToken() {
        Pipeline done = savePipeline("nt-already-notified", PipelineStatus.DONE,
                builder -> builder.notifiedAt(START.minusSeconds(5)).notifyClaimedBy("straggler-token"));

        writeBack.onFailure(done.getId(), "straggler-token");
        writeBack.onSuccess(done.getId(), "straggler-token");

        Pipeline after = reload(done);
        assertThat(after.getNotifyAttempts()).isZero();                          // 시도 횟수와 재시도 예약이 망가지지 않는다
        assertThat(after.getNotifyNextAt()).isNull();
        assertThat(after.getNotifiedAt()).isEqualTo(START.minusSeconds(5));      // 완료 시각이 다시 찍히지 않는다
    }

    @Test
    void aStaleTokenFromAnExpiredLeaseCannotWriteBackAfterReclaim() {
        Pipeline done = savePipeline("nt-stale-straggler", PipelineStatus.DONE);
        NotifyClaim expired = claimer.claimOne().orElseThrow();

        clock.advance(LEASE.plusSeconds(1));
        NotifyClaim reclaimed = claimer.claimOne().orElseThrow();
        assertThat(reclaimed.pipelineId()).isEqualTo(expired.pipelineId());
        assertThat(reclaimed.token()).isNotEqualTo(expired.token());

        writeBack.onFailure(expired.pipelineId(), expired.token());   // 점유 만료 뒤 뒤늦게 도착한 결과 기록
        Pipeline afterStale = reload(done);
        assertThat(afterStale.getNotifyAttempts()).isZero();          // 아무것도 바꾸지 못한다 — 새로 점유한 쪽의 기록을 망치지 않는다
        assertThat(afterStale.getNotifyClaimedBy()).isEqualTo(reclaimed.token());

        writeBack.onSuccess(reclaimed.pipelineId(), reclaimed.token());
        assertThat(reload(done).getNotifiedAt()).isNotNull();         // 지금 점유한 쪽은 정상적으로 기록한다
    }

    @Test
    void failureWriteBacksAdvanceBackoffExponentiallyThenGiveUpAndStayExcluded() {
        Pipeline done = savePipeline("nt-backoff-cycle", PipelineStatus.DONE);

        NotifyClaim claim = claimer.claimOne().orElseThrow();
        assertThat(writeBack.onFailure(claim.pipelineId(), claim.token())).hasValue(1);   // 올린 뒤의 횟수를 돌려준다
        Pipeline after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(1);
        assertThat(after.getNotifyNextAt()).isEqualTo(clock.instant().plus(BACKOFF_BASE));   // base × 2^0
        assertThat(after.getNotifyClaimedBy()).isNull();                                     // 점유 해제

        clock.advance(BACKOFF_BASE);   // 재시도 간격이 지나 다시 대상이 된다
        claim = claimer.claimOne().orElseThrow();
        assertThat(writeBack.onFailure(claim.pipelineId(), claim.token())).hasValue(2);
        after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(2);
        assertThat(after.getNotifyNextAt()).isEqualTo(clock.instant().plus(BACKOFF_BASE.multipliedBy(2)));   // 간격이 두 배로 늘었다

        clock.advance(BACKOFF_BASE.multipliedBy(2));
        claim = claimer.claimOne().orElseThrow();
        ListAppender<ILoggingEvent> capturedLogs = attachWriteBackListAppender();
        try {
            writeBack.onFailure(claim.pipelineId(), claim.token());   // MAX_ATTEMPTS번째 실패 → 자동 재시도 중단
        } finally {
            detachWriteBackListAppender(capturedLogs);
        }
        after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(after.getNotifyNextAt()).isEqualTo(clock.instant().plus(NotifyWriteBack.GIVE_UP_FAR_FUTURE));
        assertThat(notifyRepository.countGivenUp(MAX_ATTEMPTS)).isEqualTo(1);
        // 자동 재시도 중단은 ERROR 로그로 즉시 드러난다(진단 보조 — 경보의 기준 값은 countGivenUp 폴링이다)
        assertThat(capturedLogs.list).anySatisfy(loggingEvent -> {
            assertThat(loggingEvent.getLevel()).isEqualTo(Level.ERROR);
            assertThat(loggingEvent.getFormattedMessage())
                    .contains("give-up", "after " + MAX_ATTEMPTS + " attempts");
        });

        clock.advance(NotifyWriteBack.GIVE_UP_FAR_FUTURE.plusSeconds(1));   // 먼 미래 시각마저 지나도
        assertThat(claimer.claimOne()).isEmpty();                 // 시도 횟수 조건이 재선택을 막는다
    }

    @Test
    void theNotifyLeaseDoesNotInflateTheExecutionAdmissionCount() {
        savePipeline("nt-isolated", PipelineStatus.DONE);

        claimer.claimOne().orElseThrow();   // 알림 점유를 찍는다

        assertThat(pipelineRepository.countByClaimedUntilAfter(clock.instant())).isZero();   // 실행의 동시 실행 카운트에 잡히지 않는다
    }

    @Test
    void aCrashAfterDeliveryBeforeWriteBackAllowsReclaimAfterLeaseExpiry() {
        savePipeline("nt-crash-window", PipelineStatus.DONE);
        NotifyClaim delivered = claimer.claimOne().orElseThrow();   // 전달은 성공했지만 결과 기록 전에 죽은 상황을 흉내 낸다(onSuccess를 부르지 않는다)

        assertThat(claimer.claimOne()).isEmpty();                   // 점유가 살아 있는 동안은 이중 점유가 없다

        clock.advance(LEASE.plusSeconds(1));
        NotifyClaim reclaimed = claimer.claimOne().orElseThrow();   // 만료 후 다시 점유 → 중복 전달이 생길 수 있다(최소 한 번 전달 방침이라 감수)
        assertThat(reclaimed.pipelineId()).isEqualTo(delivered.pipelineId());
        assertThat(reclaimed.token()).isNotEqualTo(delivered.token());
    }

    @Test
    void aTerminalRowThatPredatesTheAdoptionCutoffIsNeverClaimed() {
        // 도입 시각(enabledAfter) 전에 끝난 옛 행 — 끝났고 미알림이고 재시도 시각도 지났지만 알림 대상이 아니다
        savePipeline("nt-legacy-done", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER.minusSeconds(1)));
        savePipeline("nt-legacy-failed", PipelineStatus.FAILED,
                builder -> builder.lastActivityAt(ENABLED_AFTER.minus(Duration.ofDays(30))));

        assertThat(claimer.claimOne()).isEmpty();   // 옛 행에 대한 알림이 뒤늦게 한꺼번에 쏟아지지 않는다
    }

    @Test
    void aTerminalRowAtOrAfterTheAdoptionCutoffIsClaimed() {
        // 경계 포함(>=) — 정확히 컷오프 시각에 끝난 행부터 알림 대상이다
        Pipeline atCutoff = savePipeline("nt-at-cutoff", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER));
        Pipeline afterCutoff = savePipeline("nt-after-cutoff", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER.plusSeconds(1)));

        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(atCutoff.getId());
        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(afterCutoff.getId());
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

    /** 결과 기록 쪽 로그를 붙잡는 Logback ListAppender. 자동 재시도 중단 ERROR 로그의 필드를 단언하는 데 쓴다. */
    private static ListAppender<ILoggingEvent> attachWriteBackListAppender() {
        Logger writeBackLogger = (Logger) LoggerFactory.getLogger(NotifyWriteBack.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        writeBackLogger.addAppender(listAppender);
        return listAppender;
    }

    private static void detachWriteBackListAppender(ListAppender<ILoggingEvent> listAppender) {
        ((Logger) LoggerFactory.getLogger(NotifyWriteBack.class)).detachAppender(listAppender);
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        NotifySettings notifySettings() {
            return NotifySettings.builder()
                    .enabled(true)
                    .pollInterval(Duration.ofSeconds(2))
                    .maxIdleSleep(Duration.ofSeconds(10))
                    .backoffBase(BACKOFF_BASE)
                    .backoffMax(Duration.ofMinutes(5))
                    .jitterRatio(0.0)   // 재시도 시각을 정확히 단언하기 위해 무작위 오차 없음
                    .leaseDuration(LEASE)
                    .callTimeout(Duration.ofSeconds(10))
                    .maxAttempts(MAX_ATTEMPTS)
                    .schedulerInitialDelay(Duration.ofSeconds(10))
                    .slackWebhookUrl(WEBHOOK_URL)
                    .enabledAfter(ENABLED_AFTER)   // START 이전의 고정값 — 기존 시나리오 행은 전부 컷오프 이후가 된다
                    .build();
        }
    }
}
