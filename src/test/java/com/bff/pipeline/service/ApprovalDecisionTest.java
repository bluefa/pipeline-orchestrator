package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.config.ApprovalSettings;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.TaskDetail;
import com.bff.pipeline.dto.pipeline.TaskSummary;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.enums.ApprovalChannel;
import com.bff.pipeline.enums.ApprovalStatus;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.ApprovalDeadlinePassedException;
import com.bff.pipeline.exception.ApproverRequiredException;
import com.bff.pipeline.exception.RequestNoteTooLongException;
import com.bff.pipeline.exception.RequestedByRequiredException;
import com.bff.pipeline.exception.RequestedByTooLongException;
import com.bff.pipeline.exception.TaskNotAwaitingApprovalException;
import com.bff.pipeline.model.RequestContext;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskApprovalRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.service.approval.ApprovalService;
import com.bff.pipeline.service.approval.PlanSummaryExtractor;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
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
 * 사람의 결정이 들어왔을 때 무슨 일이 벌어지는지를 실행 경로 전체로 확인한다(승인 게이트 ADR).
 * 게이트에 들어가고 기다리는 부분은 앞 PR에서 이미 고정했고, 여기서는 그 위에 결정을 얹는다.
 *
 * 고정하려는 성질은 셋이다. 결정은 한 번만 통과한다 — 승인·반려·만료가 같은 요청을 노려도 먼저 온
 * 하나만 남는다. 기한이 지난 승인은 이길 수 없다 — 만료 처리가 아직 기록되기 전이어도, 이미 기록된
 * 뒤여도 같은 답이다. 결정이 상태를 직접 쓰지 않는다 — 승인 API는 기록하고 깨울 뿐이고, 태스크와
 * 파이프라인을 옮기는 것은 워커의 마무리 트랜잭션이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class,
        TerraformJobStateRecorder.class, ConditionCheckTask.class, ApprovalGateTask.class,
        PlanSummaryExtractor.class, ApprovalService.class, ObservationRecorder.class, TaskCanceller.class,
        PipelineCreator.class, PipelineInserter.class, PipelineControl.class, RecipeCatalog.class,
        PipelineQueryService.class, ApprovalDecisionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalDecisionTest {

    private static final Instant START = Instant.parse("2026-08-22T00:00:00Z");
    private static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);
    private static final RequestContext REQUEST =
            RequestContext.of("admin@example.com", "스테이징 신규 클러스터입니다. 확인 부탁드립니다.");

    @Autowired private PipelineWorker worker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private ApprovalService approvalService;
    @Autowired private PipelineQueryService queryService;
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
    void approvalResumesTheChainAtTheApplyStep() {
        Pipeline pipeline = runToGate("gate-approve");
        Task gate = gateTask(pipeline);
        clock.advance(Duration.ofMinutes(30));

        TaskApproval decided = approvalService.approve(pipeline.getId(), gate.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE);

        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decided.getApproverName()).isEqualTo("박준호");
        assertThat(decided.getChannel()).isEqualTo(ApprovalChannel.CONSOLE);
        // 결정은 상태를 직접 쓰지 않는다 — 깨우기만 하고, 옮기는 것은 워커다.
        assertThat(reload(pipeline).getNextDueAt()).isEqualTo(clock.instant());
        assertThat(taskRepository.findById(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.AWAIT_APPROVAL);

        assertThat(worker.pollOnce()).contains(pipeline.getId());

        assertThat(taskRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(chainOf(pipeline).get(2).getStatus()).isEqualTo(TaskStatus.READY);   // Apply가 풀렸다
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.RUNNING);
    }

    /** 반려는 오류가 아니라 의도된 중단이라 실패가 아닌 취소로 닫힌다. */
    @Test
    void rejectionCancelsTheWholeRun() {
        Pipeline pipeline = runToGate("gate-reject");
        Task gate = gateTask(pipeline);

        approvalService.reject(pipeline.getId(), gate.getId(), "reviewer-1", "박준호", ApprovalChannel.CONSOLE);

        assertThat(reload(pipeline).isCancelRequested()).isTrue();
        worker.pollOnce();

        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(chainOf(pipeline)).allSatisfy(task ->
                assertThat(task.getStatus()).isIn(TaskStatus.DONE, TaskStatus.CANCELLED));
        assertThat(approvalRepository.findByTaskId(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.REJECTED);   // 반려 기록이 취소로 덮이지 않는다
    }

    /**
     * 만료가 이미 기록된 뒤에 도착한 승인도 만료 전에 늦게 도착한 승인과 같은 답을 받는다. 워커가 언제
     * 쓸고 갔느냐에 따라 답이 달라지면, 같은 사용자 행동이 초 단위 타이밍으로 성공과 실패를 오간다.
     */
    @Test
    void anApprovalArrivingAfterTheExpiryWasRecordedGetsTheSameAnswer() {
        Pipeline pipeline = runToGate("gate-expired-then-approve");
        Task gate = gateTask(pipeline);

        clock.advance(APPROVAL_TIMEOUT);
        worker.pollOnce();   // 만료 확정 — 실행은 여기서 실패로 닫힌다
        assertThat(approvalRepository.findByTaskId(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.EXPIRED);

        assertThatThrownBy(() -> approvalService.approve(pipeline.getId(), gate.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE))
                .isInstanceOf(ApprovalDeadlinePassedException.class);
    }

    /**
     * 취소로 닫힌 요청에 승인이 오면 그 행을 돌려주지 않는다. 겉보기엔 "이미 결정됨"이지만 결정한 사람이
     * 없어서, 그대로 돌려주면 승인자 없는 승인 응답이 되고 화면은 취소된 실행을 승인된 것처럼 보여 준다.
     */
    @Test
    void anApprovalOnACancelledRequestIsRejectedRatherThanEchoed() {
        Pipeline pipeline = runToGate("gate-cancelled-echo");
        Task gate = gateTask(pipeline);
        control.cancel(pipeline.getId());
        assertThat(approvalRepository.findByTaskId(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.CANCELLED);

        assertThatThrownBy(() -> approvalService.approve(pipeline.getId(), gate.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE))
                .isInstanceOf(TaskNotAwaitingApprovalException.class);
    }

    /** task 상세도 목록과 같은 승인 블록을 싣는다 — 콘솔이 상세 화면에서 승인자를 못 보면 반쪽이다. */
    @Test
    void theTaskDetailResponseCarriesTheApprovalBlock() {
        Pipeline pipeline = runToGate("gate-task-detail");
        Task gate = gateTask(pipeline);
        approvalService.approve(pipeline.getId(), gate.getId(), "reviewer-1", "박준호", ApprovalChannel.CONSOLE);

        TaskDetail detail = queryService.taskDetail(pipeline.getId(), gate.getId());

        assertThat(detail.approval()).isNotNull();
        assertThat(detail.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(detail.approval().approverName()).isEqualTo("박준호");
    }

    /** 만료 시각이 지났지만 아직 만료 처리가 기록되기 전 — 승인은 시간 조건에 막히고, 안내는 "이미 처리됨"이 아니다. */
    @Test
    void anApprovalAfterTheDeadlineLosesEvenBeforeTheExpiryIsRecorded() {
        Pipeline pipeline = runToGate("gate-late");
        Task gate = gateTask(pipeline);
        clock.advance(APPROVAL_TIMEOUT.plusMinutes(1));

        assertThatThrownBy(() -> approvalService.approve(pipeline.getId(), gate.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE))
                .isInstanceOf(ApprovalDeadlinePassedException.class);

        assertThat(approvalRepository.findByTaskId(gate.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.REQUESTED);   // 아직 아무도 만료를 기록하지 않았다
    }

    /**
     * 두 번째 결정은 기존 결정을 덮지 못하고, 오류가 아니라 이미 난 결정을 그대로 돌려받는다. 같은 결정이
     * 두 번 도착하는 것은 정상이라(재전송·중복 배달·두 번 누르기), 이미 커밋된 승인을 실패로 알리면
     * 호출자가 성공한 결정을 되돌리려 든다.
     */
    @Test
    void theSecondDecisionLosesAndSeesTheDecisionThatWon() {
        Pipeline pipeline = runToGate("gate-race");
        Task gate = gateTask(pipeline);
        approvalService.approve(pipeline.getId(), gate.getId(), "reviewer-1", "박준호", ApprovalChannel.CONSOLE);

        TaskApproval seen = approvalService.reject(pipeline.getId(), gate.getId(),
                "reviewer-2", "이서연", ApprovalChannel.SLACK);

        assertThat(seen.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(seen.getApproverId()).isEqualTo("reviewer-1");   // 진 쪽이 아니라 이긴 쪽이 담겨 돌아온다
        TaskApproval approval = approvalRepository.findByTaskId(gate.getId()).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getApproverId()).isEqualTo("reviewer-1");
        assertThat(approval.getChannel()).isEqualTo(ApprovalChannel.CONSOLE);

        // 진 반려가 취소 요청을 세우면 승인된 실행이 취소된다 — 결정을 못 남긴 쪽은 아무것도 바꾸지 않아야 한다.
        assertThat(reload(pipeline).isCancelRequested()).isFalse();
        assertThat(worker.pollOnce()).contains(pipeline.getId());
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(taskRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    /** 같은 승인이 두 번 도착해도(응답 유실 후 재전송) 같은 결정을 돌려준다 — 이것이 멱등의 실제 모습이다. */
    @Test
    void aRetriedApprovalReturnsTheSameDecision() {
        Pipeline pipeline = runToGate("gate-retry");
        Task gate = gateTask(pipeline);
        TaskApproval first = approvalService.approve(pipeline.getId(), gate.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE);
        clock.advance(Duration.ofMinutes(5));

        TaskApproval retried = approvalService.approve(pipeline.getId(), gate.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE);

        assertThat(retried.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(retried.getDecidedAt()).isEqualTo(first.getDecidedAt());   // 처음 결정 시각 그대로
    }

    @Test
    void decidingANonGateTaskIsRejected() {
        Pipeline pipeline = runToGate("gate-wrongtask");
        Task planTask = chainOf(pipeline).getFirst();

        assertThatThrownBy(() -> approvalService.approve(pipeline.getId(), planTask.getId(),
                "reviewer-1", "박준호", ApprovalChannel.CONSOLE))
                .isInstanceOf(TaskNotAwaitingApprovalException.class);
    }

    @Test
    void aDecisionWithoutAnApproverIsRejected() {
        Pipeline pipeline = runToGate("gate-noapprover");
        Task gate = gateTask(pipeline);

        assertThatThrownBy(() -> approvalService.approve(pipeline.getId(), gate.getId(),
                "  ", "박준호", ApprovalChannel.CONSOLE))
                .isInstanceOf(ApproverRequiredException.class);
    }

    @Test
    void theDetailResponseCarriesTheRequestContextAndTheApprovalBlock() {
        Pipeline pipeline = runToGate("gate-detail");

        PipelineDetail detail = queryService.detail(pipeline.getId());

        assertThat(detail.status()).isEqualTo(PipelineStatus.AWAIT_APPROVAL);
        assertThat(detail.requestedBy()).isEqualTo("admin@example.com");
        assertThat(detail.requestNote()).isEqualTo("스테이징 신규 클러스터입니다. 확인 부탁드립니다.");
        assertThat(detail.currentTaskSequence()).isEqualTo(1);   // 승인 대기도 "지금 서 있는 자리"다
        TaskSummary gateSummary = detail.tasks().get(1);
        assertThat(gateSummary.approval()).isNotNull();
        assertThat(gateSummary.approval().status()).isEqualTo(ApprovalStatus.REQUESTED);
        assertThat(gateSummary.approval().expiresAt()).isEqualTo(START.plus(APPROVAL_TIMEOUT));
        assertThat(detail.tasks().getFirst().approval()).isNull();   // 게이트가 아닌 task엔 블록이 없다
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
