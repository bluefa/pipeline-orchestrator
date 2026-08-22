package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.config.ApprovalSettings;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.enums.ApprovalStatus;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.RequestedByRequiredException;
import com.bff.pipeline.model.RequestContext;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskApprovalRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.service.approval.PlanSummaryExtractor;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.task.ApprovalGateTask;
import com.bff.pipeline.service.task.ConditionCheckTask;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskStateMachine;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.terraform.TerraformJobStateRecorder;
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게이트에 들어가고, 기다리고, 기한이 지나면 닫히는 데까지를 실행 경로로 확인한다. 승인 설정을 켠
 * 컨텍스트라 새 실행은 서비스 Plan과 Apply 사이에 승인 단계가 들어간 레시피로 만들어진다.
 *
 * 여기서 고정하려는 성질은 셋이다. 대기는 자원을 쥐지 않는다 — 게이트에 들어가면 파이프라인은 만료
 * 시각까지 아예 잡히지 않는다. 대기 중인데 요청이 없는 조합은 생기지 않는다 — 요청 행 생성과 상태
 * 전이가 한 트랜잭션이다. 열린 요청은 실행이 끝날 때 함께 닫힌다 — 만료로든 취소로든.
 *
 * 사람이 승인·반려하는 경로는 여기 없다. 그 API와 그것이 만드는 경합(승인 대 만료, 중복 결정)은
 * 결정 API와 함께 들어온다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class,
        TerraformJobStateRecorder.class, ConditionCheckTask.class, ApprovalGateTask.class,
        PlanSummaryExtractor.class, ObservationRecorder.class, TaskCanceller.class,
        PipelineCreator.class, PipelineInserter.class,
        PipelineControl.class, RecipeCatalog.class, ApprovalGateEntryTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalGateEntryTest {

    private static final Instant START = Instant.parse("2026-08-22T00:00:00Z");
    private static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);
    private static final RequestContext REQUEST =
            RequestContext.of("admin@example.com", "스테이징 신규 클러스터입니다. 확인 부탁드립니다.");

    /** 승인자가 보게 될 값이 실제로 나오는지 보려면 진짜 plan 로그 모양이 필요하다. */
    private static final String PLAN_LOG = """
            Terraform will perform the following actions:

              # aws_instance.web will be created
              # aws_s3_bucket.logs will be destroyed
              # aws_db_instance.main must be replaced

            Plan: 2 to add, 0 to change, 2 to destroy.
            """;

    @Autowired private PipelineWorker worker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private PlanSummaryExtractor planSummaryExtractor;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskApprovalRepository approvalRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private TerraformResultRepository terraformResultRepository;
    @Autowired private TerraformJobStateRepository terraformJobStateRepository;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManagerClient;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
        infraManagerClient.onResult(() -> PLAN_LOG);
    }

    @AfterEach
    void clean() {
        approvalRepository.deleteAll();
        terraformResultRepository.deleteAll();
        terraformJobStateRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void theGatedRecipeIsChosenWhenApprovalIsOn() {
        Pipeline pipeline = creator.create("gate-recipe", PipelineType.INSTALL, REQUEST);

        assertThat(pipeline.getRecipeDefinition()).isEqualTo("AWS_INSTALL_WITH_ADMIN_CONSENT_V1");
        assertThat(chainOf(pipeline)).extracting(Task::getTaskDefinition).containsExactly(
                TaskDefinition.AWS_SERVICE_PLAN_V1.name(),
                TaskDefinition.AWS_SERVICE_APPLY_APPROVAL_V1.name(),   // Plan 바로 뒤, Apply 바로 앞
                TaskDefinition.AWS_SERVICE_APPLY_V1.name(),
                TaskDefinition.NETWORK_READY_V1.name(),
                TaskDefinition.AWS_BDC_COMMON_PLAN_V1.name(),
                TaskDefinition.AWS_BDC_COMMON_APPLY_V1.name(),
                TaskDefinition.AWS_BDC_SERVICE_LEVEL_PLAN_V1.name(),
                TaskDefinition.AWS_BDC_SERVICE_LEVEL_APPLY_V1.name());
    }

    /** 대기 진입의 핵심은 "만료 시각까지 잡히지 않는다"이다 — 이게 깨지면 매 sweep마다 헛돌게 된다. */
    @Test
    void enteringTheGateParksThePipelineUntilTheDeadline() {
        Pipeline pipeline = runToGate("gate-park");

        Pipeline parked = reload(pipeline);
        assertThat(parked.getStatus()).isEqualTo(PipelineStatus.AWAIT_APPROVAL);
        assertThat(parked.getNextDueAt()).isEqualTo(START.plus(APPROVAL_TIMEOUT));
        assertThat(parked.getClaimedBy()).isNull();
        // 실행 정원은 상태가 아니라 활성 claim 수로 세므로, 대기 중인 실행은 정원을 갉아먹지 않는다.
        assertThat(parked.getClaimedUntil()).isNull();
        assertThat(pipelineRepository.countByClaimedUntilAfter(START)).isZero();
        assertThat(gateTask(parked).getStatus()).isEqualTo(TaskStatus.AWAIT_APPROVAL);

        // 만료 전에는 아무리 돌려도 잡히지 않는다 — 승인 대기가 실행 자원을 소비하지 않는다는 약속.
        clock.advance(Duration.ofHours(23));
        assertThat(worker.pollOnce()).isEmpty();
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.AWAIT_APPROVAL);
    }

    /** 승인 요청은 게이트에 들어가는 그 트랜잭션에서 함께 만들어진다 — 대기 중인데 요청이 없는 조합은 없다. */
    @Test
    void enteringTheGateCreatesTheApprovalRequestWithASummaryOfThePlan() {
        Pipeline pipeline = runToGate("gate-request");

        TaskApproval approval = approvalRepository.findByTaskId(gateTask(pipeline).getId()).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.REQUESTED);
        assertThat(approval.getRequestedAt()).isEqualTo(START);
        assertThat(approval.getExpiresAt()).isEqualTo(START.plus(APPROVAL_TIMEOUT));
        assertThat(approval.getDecidedAt()).isNull();
        assertThat(approval.getPlanSummary())
                .contains("\"verified\":true")
                .contains("\"create_count\":1")
                .contains("\"destroy_count\":1")
                .contains("\"replace_count\":1")
                .contains("aws_db_instance.main");   // 위험한 것(교체)이 목록 앞에 남는다
    }


    /**
     * 원천 로그가 온전하지 않으면 수치를 아예 내보내지 않는다. 얼추 맞는 요약을 근거로 승인이 나가는 것이
     * 이 기능에서 가장 나쁜 결말이라, 못 읽었으면 못 읽었다고 말한다.
     */
    @Test
    void anUnreadablePlanLogYieldsAnUnverifiedSummaryButStillEntersTheGate() {
        infraManagerClient.onResult(() -> "terraform: ok");   // 변경 목록도 합계도 없는 로그

        Pipeline pipeline = runToGate("gate-unverified");

        TaskApproval approval = approvalRepository.findByTaskId(gateTask(pipeline).getId()).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.REQUESTED);   // 요약 실패가 게이트를 막지 않는다
        assertThat(approval.getPlanSummary())
                .contains("\"verified\":false")
                .contains("합계가 맞지 않습니다")
                // 수치를 아예 내보내지 않는다 — 0을 실으면 "0건 생성"으로 읽혀 모른다는 사실이 지워진다.
                .doesNotContain("create_count", "destroy_count", "omitted_count");
    }


    /**
     * job 하나의 로그가 통째로 사라져도 검증됨으로 나가면 안 된다. "던진 job이 전부 몇 개였나"의 근거를
     * 저장 실패를 삼키는 관찰 테이블에서 가져오면, 그 job의 관찰까지 함께 빠졌을 때 애초에 없던 job으로
     * 착각해 반쪽짜리 요약이 통과한다 — 근거는 상태 전이와 함께 저장되는 dispatch 응답이어야 한다.
     */
    @Test
    void aPlanMissingOneJobsLogIsNeverReportedAsVerified() {
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\"]");
        Pipeline pipeline = runToGate("gate-partial");
        Task gate = gateTask(pipeline);
        assertThat(planSummaryExtractor.summarize(gate)).contains("\"verified\":true");

        // job-2의 흔적을 양쪽 관찰 테이블에서 모두 지운다 — 두 기록 모두 best-effort라 함께 빠질 수 있다.
        Task planStep = chainOf(pipeline).getFirst();
        terraformResultRepository.findByTaskIdAndAttemptNumberAndJobId(planStep.getId(), 1, "job-2")
                .ifPresent(terraformResultRepository::delete);
        terraformJobStateRepository.deleteAll();

        assertThat(planSummaryExtractor.summarize(gate))
                .contains("\"verified\":false")
                .contains("일부 Plan 작업의 로그가 없습니다")
                .doesNotContain("create_count");
    }


    /** 기한이 지나면 실행이 실패로 닫히고, 재시도하지 않는다 — 승인은 다시 시도해 결과가 달라지지 않는다. */
    @Test
    void anUndecidedGateExpiresAndFailsTheRun() {
        Pipeline pipeline = runToGate("gate-expire");
        Task gate = gateTask(pipeline);

        clock.advance(APPROVAL_TIMEOUT);
        assertThat(worker.pollOnce()).contains(pipeline.getId());

        assertThat(taskRepository.findById(gate.getId()).orElseThrow())
                .satisfies(task -> {
                    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
                    assertThat(task.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_EXPIRED);
                });
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.FAILED);
        assertThat(approvalRepository.findByTaskId(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);
    }

    /** 대기 중 취소는 열려 있던 요청도 함께 닫는다 — 안 닫으면 끝난 실행에 살아 있는 요청이 남는다. */
    @Test
    void cancellingWhileAwaitingClosesTheOpenRequest() {
        Pipeline pipeline = runToGate("gate-cancel");
        Task gate = gateTask(pipeline);

        control.cancel(pipeline.getId());

        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(approvalRepository.findByTaskId(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.CANCELLED);
    }

    /** 요청자를 모르는 승인 요청은 감사가 성립하지 않는다 — 게이트가 든 레시피에서만 필수로 건다. */
    @Test
    void aGatedRunRequiresARequester() {
        assertThatThrownBy(() ->
                creator.create("gate-norequester", PipelineType.INSTALL, RequestContext.none()))
                .isInstanceOf(RequestedByRequiredException.class);
        assertThat(pipelineRepository.count()).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    /** Plan을 성공까지 돌려 게이트가 대기에 들어간 상태를 만든다(dispatch → poll → 게이트 진입). */
    private Pipeline runToGate(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.INSTALL, REQUEST);
        worker.pollOnce();   // Plan dispatch
        worker.pollOnce();   // Plan 성공 판정 → 게이트가 READY로 풀린다
        worker.pollOnce();   // 게이트 진입 → AWAIT_APPROVAL
        return pipeline;
    }

    private Pipeline reload(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow();
    }

    private List<Task> chainOf(Pipeline pipeline) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
    }

    private Task gateTask(Pipeline pipeline) {
        return chainOf(pipeline).stream()
                .filter(task -> task.getOperation().isApprovalGate())
                .findFirst().orElseThrow();
    }

    @TestConfiguration
    static class Wiring {

        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeInfraManagerClient infraManagerClient() {
            return new FakeInfraManagerClient();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /** 이 테스트만 승인을 켠다 — 켜야 게이트가 든 레시피로 실행이 만들어진다. */
        @Bean
        ApprovalSettings approvalSettings() {
            return ApprovalSettings.builder().enabled(true).timeout(APPROVAL_TIMEOUT).build();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2)
                    .maxTerraformPollCallErrors(10)
                    .startDelay(Duration.ZERO)
                    .build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }
    }
}
