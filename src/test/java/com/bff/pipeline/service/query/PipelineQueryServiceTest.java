package com.bff.pipeline.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.PipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineSummary;
import com.bff.pipeline.dto.pipeline.TaskDetail;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskCheck;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.StatisticsPeriod;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.TaskNotFoundException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PipelineQueryService의 조회/집계/파생을 검증한다. 실행 tx 경계를 있는 그대로 두려고 클래스 tx를
 * NOT_SUPPORTED로 억제하고(SKILL §4), 각 테스트 뒤 deleteAll로 정리한다. Clock은 고정값을 주입한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PipelineQueryService.class, PipelineQueryServiceTest.Wiring.class})
class PipelineQueryServiceTest {

    static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired PipelineQueryService service;
    @Autowired PipelineRepository pipelines;
    @Autowired TaskRepository tasks;
    @Autowired TaskAttemptRepository attempts;
    @Autowired TaskCheckRepository checks;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        checks.deleteAll();
        attempts.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void liveStatisticsCountsRunningPipelinesInProgressSlotsAndConfiguredCaps() {
        Pipeline running = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW));
        save(pipeline(PipelineStatus.RUNNING, "t-2", NOW));
        save(pipeline(PipelineStatus.PENDING, "t-4", NOW));             // 시작 지연 대기
        save(pipeline(PipelineStatus.DONE, "t-3", NOW));
        save(task(running.getId(), 0, TaskStatus.IN_PROGRESS, true));   // 슬롯 점유 중

        LivePipelineStatistics stats = service.liveStatistics();

        assertThat(stats.runningPipelineCount()).isEqualTo(2);          // PENDING은 running에 안 셈
        assertThat(stats.pendingPipelineCount()).isEqualTo(1);          // PENDING 별도 노출(LIN-30)
        assertThat(stats.inProgressTerraformTaskCount()).isEqualTo(1);
        assertThat(stats.terraformSlotCap()).isEqualTo(20);
        assertThat(stats.runningPipelineCap()).isEqualTo(100);
    }

    @Test
    void statisticsCountsByStatusWithinThePeriodWindow() {
        save(pipeline(PipelineStatus.RUNNING, "t-1", NOW.minus(Duration.ofHours(2))));   // 하루 안
        save(pipeline(PipelineStatus.PENDING, "t-4", NOW.minus(Duration.ofHours(1))));   // 하루 안, 시작 지연 대기
        save(pipeline(PipelineStatus.DONE, "t-2", NOW.minus(Duration.ofHours(5))));      // 하루 안
        save(pipeline(PipelineStatus.FAILED, "t-3", NOW.minus(Duration.ofDays(3))));     // 하루 밖 → 제외

        PipelineStatistics stats = service.statistics(StatisticsPeriod.ONE_DAY);

        assertThat(stats.since()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(stats.pendingCount()).isEqualTo(1);       // PENDING 버킷(LIN-30)
        assertThat(stats.runningCount()).isEqualTo(1);
        assertThat(stats.doneCount()).isEqualTo(1);
        assertThat(stats.failedCount()).isZero();
        assertThat(stats.totalCount()).isEqualTo(3);         // PENDING 포함
    }

    @Test
    void listFiltersByStatusAndComputesProgress() {
        Pipeline running = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW));
        save(pipeline(PipelineStatus.DONE, "t-2", NOW));
        save(task(running.getId(), 0, TaskStatus.DONE, true));
        save(task(running.getId(), 1, TaskStatus.IN_PROGRESS, false));

        Page<PipelineSummary> page = service.list(PipelineStatus.RUNNING, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        PipelineSummary summary = page.getContent().getFirst();
        assertThat(summary.pipelineId()).isEqualTo(running.getId());
        assertThat(summary.doneTaskCount()).isEqualTo(1);
        assertThat(summary.totalTaskCount()).isEqualTo(2);
    }

    @Test
    void detailDerivesCurrentTaskLeaseAndCancelFlag() {
        Pipeline pipeline = pipeline(PipelineStatus.RUNNING, "t-1", NOW);
        pipeline.setClaimedUntil(NOW.plus(Duration.ofMinutes(1)));   // 미래 → leased
        pipeline.setCancelRequested(true);
        pipeline = save(pipeline);
        save(task(pipeline.getId(), 0, TaskStatus.DONE, true));
        save(task(pipeline.getId(), 1, TaskStatus.IN_PROGRESS, false));

        PipelineDetail detail = service.detail(pipeline.getId());

        assertThat(detail.currentTaskSequence()).isEqualTo(1);
        assertThat(detail.finalTaskSequence()).isEqualTo(1);
        assertThat(detail.doneTaskCount()).isEqualTo(1);
        assertThat(detail.totalTaskCount()).isEqualTo(2);
        assertThat(detail.leased()).isTrue();
        assertThat(detail.cancelRequested()).isTrue();
        assertThat(detail.currentMaxFailCount()).isEqualTo(2);   // 전역 기본(PipelineSettings)
    }

    @Test
    void detailReportsZeroDueLagForATerminalPipeline() {
        // nextDueAt이 과거(NOW-1d)에 멈춰 있어도 종료 상태면 지연으로 세지 않는다
        Pipeline done = save(pipeline(PipelineStatus.DONE, "t-1", NOW.minus(Duration.ofDays(1))));

        assertThat(service.detail(done.getId()).dueLagMillis()).isZero();
    }

    @Test
    void detailForAMissingPipelineThrowsNotFound() {
        assertThatThrownBy(() -> service.detail(9999L)).isInstanceOf(PipelineNotFoundException.class);
    }

    @Test
    void taskDetailForATaskOfAnotherPipelineThrowsTaskNotFound() {
        Pipeline owner = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW));
        Pipeline other = save(pipeline(PipelineStatus.RUNNING, "t-2", NOW));
        Task foreign = save(task(other.getId(), 0, TaskStatus.READY, true));

        assertThatThrownBy(() -> service.taskDetail(owner.getId(), foreign.getId()))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void taskDetailReturnsEffectiveSettingsAndAttemptWithCheck() {
        Pipeline pipeline = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW));
        Task task = save(task(pipeline.getId(), 0, TaskStatus.IN_PROGRESS, true));
        TaskAttempt attempt = attempts.save(TaskAttempt.builder()
                .taskId(task.getId()).attemptNumber(1).status(TaskStatus.IN_PROGRESS)
                .response("{\"jobIds\":[\"j-1\"]}").startedAt(NOW).build());
        checks.save(TaskCheck.builder().taskAttemptId(attempt.getId())
                .callCount(3).notMetCount(2).apiErrorCount(1).callTimeoutCount(0)
                .lastExternalStatus("RUNNING").lastCheckedAt(NOW).build());

        TaskDetail detail = service.taskDetail(pipeline.getId(), task.getId());

        assertThat(detail.effectiveMaxFailCount()).isEqualTo(2);
        assertThat(detail.effectiveExecutionTimeout()).isEqualTo(Duration.ofMinutes(50));
        assertThat(detail.definition()).isNotNull();   // 카탈로그 해석 성공 → 실행 계약 뷰 동봉
        assertThat(detail.definition().name()).isEqualTo("AWS_SERVICE_APPLY_V1");
        assertThat(detail.definition().dispatchApi()).startsWith("POST ");
        assertThat(detail.definition().successPolicy()).isNotBlank();
        assertThat(detail.attempts()).hasSize(1);
        assertThat(detail.attempts().getFirst().response()).contains("j-1");
        assertThat(detail.attempts().getFirst().check().callCount()).isEqualTo(3);
    }

    @Test
    void taskDetailWithALegacyOperationValueDegradesInsteadOfThrowing() {
        // 카탈로그에서 제거된 옛 operation 값이 남은 terminal history 행 — @Enumerated였다면 read 자체가 터진다.
        Pipeline pipeline = save(pipeline(PipelineStatus.DONE, "t-1", NOW));
        Task legacy = save(task(pipeline.getId(), 0, TaskStatus.DONE, true));
        jdbcTemplate.update("update task set operation = 'APPLY_NETWORK' where id = ?", legacy.getId());

        TaskDetail detail = service.taskDetail(pipeline.getId(), legacy.getId());

        assertThat(detail.operation()).isNull();                    // 미해석 → null 열화(이름은 task_definition에 남는다)
        assertThat(detail.effectiveExecutionTimeout()).isNull();    // operation 파생 표시값도 조용히 비운다
        assertThat(detail.taskDefinition()).isEqualTo("AWS_SERVICE_APPLY_V1");
    }

    @Test
    void taskDetailWithAStaleDefinitionNameOmitsTheDefinitionView() {
        Pipeline pipeline = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW));
        Task stale = task(pipeline.getId(), 0, TaskStatus.IN_PROGRESS, true);
        stale.setTaskDefinition("AWS_SERVICE_APPLY_V0");   // 카탈로그에 없는 옛 이름 → 뷰 없이 이름만 노출
        stale = save(stale);

        TaskDetail detail = service.taskDetail(pipeline.getId(), stale.getId());

        assertThat(detail.taskDefinition()).isEqualTo("AWS_SERVICE_APPLY_V0");
        assertThat(detail.definition()).isNull();
    }

    @Test
    void taskDetailForAConditionCheckReportsNoExecutionTimeoutButKeepsMaxFailCount() {
        Pipeline pipeline = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW));
        Task conditionTask = save(task(pipeline.getId(), 0, TaskStatus.IN_PROGRESS, false));   // NETWORK_READY

        TaskDetail detail = service.taskDetail(pipeline.getId(), conditionTask.getId());

        assertThat(detail.effectiveExecutionTimeout()).isNull();   // #15: TERRAFORM_JOB 전용
        assertThat(detail.effectiveMaxFailCount()).isEqualTo(2);   // 조건 체크는 maxFailCount로 경계
    }

    @Test
    void latestByTargetReturnsTheMostRecentRunOrEmptyWhenNone() {
        save(pipeline(PipelineStatus.DONE, "t-1", NOW.minus(Duration.ofDays(2))));
        Pipeline newer = save(pipeline(PipelineStatus.RUNNING, "t-1", NOW.minus(Duration.ofHours(1))));

        assertThat(service.latestByTarget("t-1")).map(PipelineSummary::pipelineId).contains(newer.getId());
        assertThat(service.latestByTarget("absent")).isEmpty();
    }

    private Pipeline save(Pipeline pipeline) {
        return pipelines.save(pipeline);
    }

    private Task save(Task task) {
        return tasks.save(task);
    }

    private static Pipeline pipeline(PipelineStatus status, String target, Instant createdAt) {
        return Pipeline.builder()
                .type(PipelineType.INSTALL)
                .target(target)
                .cloudProvider(CloudProvider.AWS)
                .recipeDefinition("AWS_INSTALL_V1")
                .status(status)
                .createdAt(createdAt)
                .lastActivityAt(createdAt)
                .activeTarget(status.isTerminal() ? null : target)   // 비종단(RUNNING·PENDING)만 슬롯 점유
                .nextDueAt(createdAt)
                .cancelRequested(false)
                .build();
    }

    private static Task task(Long pipelineId, int sequence, TaskStatus status, boolean consumesSlot) {
        TaskOperation operation = consumesSlot ? TaskOperation.AWS_SERVICE_TF_APPLY : TaskOperation.NETWORK_READY;
        return Task.builder()
                .pipelineId(pipelineId)
                .sequence(sequence)
                .taskName(operation.mechanism())
                .operation(operation)
                .taskDefinition(consumesSlot ? "AWS_SERVICE_APPLY_V1" : "NETWORK_READY_V1")
                .consumesTerraformSlot(consumesSlot)
                .status(status)
                .failCount(0)
                .build();
    }

    @TestConfiguration
    static class Wiring {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(4)
                    .leaseDuration(Duration.ofMinutes(2))
                    .apiCallTimeout(Duration.ofSeconds(30))
                    .runningPipelineCap(100)
                    .terraformSlotCap(20)
                    .terraformSlotRetry(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(1))
                    .maxIdleSleep(Duration.ofSeconds(5))
                    .backoffBase(Duration.ofMillis(200))
                    .backoffMax(Duration.ofSeconds(5))
                    .jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2)
                    .startDelay(Duration.ZERO)
                    .build();
        }
    }
}
