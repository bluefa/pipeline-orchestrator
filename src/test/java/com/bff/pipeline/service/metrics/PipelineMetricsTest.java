package com.bff.pipeline.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link PipelineMetrics}를 실제 DB(H2) 위에서 검증한다. 게이지는 저장소 질의로 값을 계산하므로
 * 진짜 행을 넣고 읽어야 조건(집을 수 있는 행만, 재시도 대기 행 제외 등)까지 함께 검증된다.
 * 카운터는 커밋-후 증가 계약과 태그 분류를 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineMetricsTest {

    static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");
    static final Instant NOTIFY_ENABLED_AFTER = NOW.minus(Duration.ofHours(1));

    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private PipelineMetrics metrics(boolean notifyEnabled) {
        NotifySettings settings = notifyEnabled
                ? NotifySettings.builder().enabled(true).slackWebhookUrl("https://hooks.example/x")
                        .enabledAfter(NOTIFY_ENABLED_AFTER).environment("test")
                        .detailUrlBase("http://localhost/pipelines").build()
                : NotifySettings.builder().build();
        return new PipelineMetrics(meterRegistry, pipelineRepository, taskRepository, settings, clock);
    }

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ── 게이지: 진행성(due lag) ────────────────────────────────────────────────────────────

    @Test
    void dueLagIsTheAgeOfTheOldestClaimableDuePipeline() {
        savePipeline("t-lag", PipelineStatus.RUNNING, row -> row.nextDueAt(NOW.minusSeconds(90)));
        savePipeline("t-fresh", PipelineStatus.RUNNING, row -> row.nextDueAt(NOW.minusSeconds(10)));
        metrics(false);

        assertThat(gaugeValue(PipelineMetrics.PIPELINE_DUE_LAG)).isEqualTo(90.0);
    }

    @Test
    void dueLagIgnoresRowsHeldByALiveClaim() {
        savePipeline("t-claimed", PipelineStatus.RUNNING, row -> row.nextDueAt(NOW.minusSeconds(300))
                .claimedBy("worker-1").claimedUntil(NOW.plusSeconds(60)));
        metrics(false);

        assertThat(gaugeValue(PipelineMetrics.PIPELINE_DUE_LAG)).isZero();
    }

    @Test
    void dueLagIsZeroWhenNothingIsLate() {
        savePipeline("t-future", PipelineStatus.PENDING, row -> row.nextDueAt(NOW.plusSeconds(30)));
        metrics(false);

        assertThat(gaugeValue(PipelineMetrics.PIPELINE_DUE_LAG)).isZero();
    }

    // ── 게이지: 테라폼 슬롯 ───────────────────────────────────────────────────────────────

    @Test
    void slotGaugeCountsOnlyInProgressSlotConsumingTasks() {
        Pipeline pipeline = savePipeline("t-slot", PipelineStatus.RUNNING, row -> { });
        saveTask(pipeline.getId(), 0, TaskStatus.IN_PROGRESS, true);
        saveTask(pipeline.getId(), 1, TaskStatus.READY, true);         // 아직 미실행 — 제외
        saveTask(pipeline.getId(), 2, TaskStatus.IN_PROGRESS, false);  // 슬롯 비소비 — 제외
        metrics(false);

        assertThat(gaugeValue(PipelineMetrics.TERRAFORM_SLOT_OCCUPIED)).isEqualTo(1.0);
    }

    // ── 게이지: 알림(give-up, 지연 나이) ─────────────────────────────────────────────────

    @Test
    void giveUpGaugeCountsPipelinesWaitingForAnOperator() {
        savePipeline("t-giveup", PipelineStatus.FAILED, row -> row.notifyAttempts(3));
        savePipeline("t-retrying", PipelineStatus.FAILED, row -> row.notifyAttempts(1));
        metrics(false);

        assertThat(gaugeValue(PipelineMetrics.NOTIFY_GIVE_UP)).isEqualTo(1.0);
    }

    @Test
    void pendingAgeGaugeExistsOnlyWhenNotifyIsEnabled() {
        metrics(false);
        assertThat(meterRegistry.find(PipelineMetrics.NOTIFY_PENDING_AGE).gauge()).isNull();
    }

    @Test
    void pendingAgeIsTheAgeOfTheOldestNotifiablePipeline() {
        savePipeline("t-old", PipelineStatus.DONE, row -> row.lastActivityAt(NOW.minusSeconds(120)));
        savePipeline("t-young", PipelineStatus.DONE, row -> row.lastActivityAt(NOW.minusSeconds(20)));
        savePipeline("t-giveup", PipelineStatus.DONE,
                row -> row.lastActivityAt(NOW.minusSeconds(999)).notifyAttempts(3));            // 멈춘 건 — 제외
        savePipeline("t-backoff", PipelineStatus.DONE,
                row -> row.lastActivityAt(NOW.minusSeconds(888)).notifyNextAt(NOW.plusSeconds(60))); // 재시도 대기 — 제외
        metrics(true);

        assertThat(gaugeValue(PipelineMetrics.NOTIFY_PENDING_AGE)).isEqualTo(120.0);
    }

    // ── 카운터 ─────────────────────────────────────────────────────────────────────────────

    @Test
    void terminalCounterIncrementsImmediatelyOutsideATransaction() {
        metrics(false).pipelineTerminalized(PipelineStatus.DONE, PipelineType.INSTALL);
        assertThat(terminalCount("DONE", "INSTALL")).isEqualTo(1.0);
    }

    @Test
    void terminalCounterWaitsForTheCommitInsideATransaction() {
        PipelineMetrics pipelineMetrics = metrics(false);
        TransactionSynchronizationManager.initSynchronization();

        pipelineMetrics.pipelineTerminalized(PipelineStatus.FAILED, PipelineType.DELETE);
        assertThat(terminalCount("FAILED", "DELETE")).isZero();   // 커밋 전에는 세지 않는다

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(terminalCount("FAILED", "DELETE")).isEqualTo(1.0);
    }

    @Test
    void aPipelineWithoutATypeCountsUnderTheUnknownTag() {
        metrics(false).pipelineTerminalized(PipelineStatus.CANCELLED, null);
        assertThat(terminalCount("CANCELLED", PipelineMetrics.TYPE_UNKNOWN)).isEqualTo(1.0);
    }

    @Test
    void attemptFailuresCountUnderTheirErrorCode() {
        PipelineMetrics pipelineMetrics = metrics(false);
        pipelineMetrics.taskAttemptFailed(ErrorCode.CALL_TIMEOUT);
        pipelineMetrics.taskAttemptFailed(ErrorCode.CALL_TIMEOUT);
        pipelineMetrics.taskAttemptFailed(ErrorCode.JOB_FAILED);

        assertThat(meterRegistry.counter(PipelineMetrics.TASK_ATTEMPT_FAILURE,
                PipelineMetrics.ERROR_CODE_TAG, "CALL_TIMEOUT").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter(PipelineMetrics.TASK_ATTEMPT_FAILURE,
                PipelineMetrics.ERROR_CODE_TAG, "JOB_FAILED").count()).isEqualTo(1.0);
    }

    @Test
    void staleWriteBacksAreCounted() {
        metrics(false).staleWriteBackDiscarded();
        assertThat(meterRegistry.counter(PipelineMetrics.STALE_WRITE_BACK).count()).isEqualTo(1.0);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────────────────

    private Pipeline savePipeline(String target, PipelineStatus status,
            Consumer<Pipeline.PipelineBuilder> customize) {
        Pipeline.PipelineBuilder builder = Pipeline.builder()
                .type(PipelineType.INSTALL)
                .target(target)
                .status(status)
                .createdAt(NOW.minus(Duration.ofMinutes(10)))
                .lastActivityAt(NOW.minus(Duration.ofMinutes(1)))
                .nextDueAt(NOW)
                .cancelRequested(false);
        customize.accept(builder);
        return pipelineRepository.save(builder.build());
    }

    private void saveTask(Long pipelineId, int sequence, TaskStatus status, boolean consumesTerraformSlot) {
        TaskDefinition definition = TaskDefinition.AWS_SERVICE_APPLY_V1;
        taskRepository.save(Task.builder()
                .pipelineId(pipelineId)
                .sequence(sequence)
                .taskName(definition.mechanism())
                .operation(definition.operation())
                .taskDefinition(definition.name())
                .status(status)
                .consumesTerraformSlot(consumesTerraformSlot)
                .failCount(0)
                .build());
    }

    private double gaugeValue(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double terminalCount(String status, String type) {
        return meterRegistry.counter(PipelineMetrics.PIPELINE_TERMINAL,
                PipelineMetrics.STATUS_TAG, status, PipelineMetrics.TYPE_TAG, type).count();
    }
}
