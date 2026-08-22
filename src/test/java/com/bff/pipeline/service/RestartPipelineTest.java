package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.controller.TargetSourcePipelineController;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.RestartPipelineRequest;
import com.bff.pipeline.dto.pipeline.RestartPreview;
import com.bff.pipeline.dto.pipeline.TaskSummary;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.InvalidResumeSequenceException;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.PipelineNotLatestException;
import com.bff.pipeline.exception.PipelineNotRestartableException;
import com.bff.pipeline.exception.UnknownTaskException;
import com.bff.pipeline.model.PipelinePlan;
import com.bff.pipeline.model.PipelinePlan.PlannedStep;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.PipelineRestarter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
import com.bff.pipeline.model.RequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
 * 파이프라인 재시작(재시작 설계 결정 1–5). 재시작 = 원본 체인의 첫 non-DONE task부터의 suffix로 만드는 새
 * 파이프라인이며(terminal 불부활), type/recipe/provider는 원본에서 승계하고 계보는 origin_pipeline_id/
 * origin_task_id로만 남는다. 허용 대상은 "최신 + FAILED/CANCELLED"뿐이다 — 특히 DONE 거절은 active_target
 * 백스톱이 없어 명시 검사가 유일 방어선이라는 점을 회귀로 고정한다. preview는 실행과 같은 검증을 공유해
 * 불가 상태에서는 미리보기부터 409다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineRestarter.class, PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class,
        PipelineQueryService.class, TargetSourcePipelineController.class, RestartPipelineTest.Wiring.class, ApprovalTestWiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RestartPipelineTest {

    private static final Instant START = Instant.parse("2026-07-25T00:00:00Z");
    private static final List<TaskDefinition> CHAIN_DEFINITIONS = List.of(
            TaskDefinition.AWS_SERVICE_PLAN_V1, TaskDefinition.AWS_SERVICE_APPLY_V1,
            TaskDefinition.AWS_BDC_COMMON_PLAN_V1, TaskDefinition.AWS_BDC_COMMON_APPLY_V1);

    @Autowired private PipelineRestarter restarter;
    @Autowired private TargetSourcePipelineController controller;
    @Autowired private PipelineQueryService queryService;
    @Autowired private PipelineInserter inserter;
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
    void failedPipelineRestartsFromFailedTask() {
        Pipeline origin = seedTerminal("rst-failed", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
        List<Task> originChain = chainOf(origin);

        Pipeline restarted = restarter.restart("rst-failed", origin.getId(), null, RequestContext.none());

        assertThat(restarted.getId()).isNotEqualTo(origin.getId());   // terminal 불부활 — 항상 새 행
        assertThat(restarted.getType()).isEqualTo(PipelineType.INSTALL);   // CUSTOM으로 위장하지 않는다(결정 1)
        assertThat(restarted.getRecipeDefinition()).isEqualTo("AWS_INSTALL_V1");
        assertThat(restarted.getCloudProvider()).isEqualTo(CloudProvider.AWS);
        assertThat(restarted.getOriginPipelineId()).isEqualTo(origin.getId());
        List<Task> chain = chainOf(restarted);
        assertThat(chain).extracting(Task::getTaskDefinition, Task::getSequence, Task::getOriginTaskId)
                .containsExactly(
                        tuple(TaskDefinition.AWS_BDC_COMMON_PLAN_V1.name(), 0, originChain.get(2).getId()),
                        tuple(TaskDefinition.AWS_BDC_COMMON_APPLY_V1.name(), 1, originChain.get(3).getId()));
        assertThat(chain.getFirst().getStatus()).isEqualTo(TaskStatus.READY);   // fresh run — 첫 task READY
        assertThat(chain).allSatisfy(task -> assertThat(task.getFailCount()).isZero());
        assertThat(chain.getLast().getStatus()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void cancelledPipelineRestartsFromCancelPoint() {
        Pipeline origin = seedTerminal("rst-cancelled", PipelineStatus.CANCELLED,
                TaskStatus.DONE, TaskStatus.CANCELLED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);

        Pipeline restarted = restarter.restart("rst-cancelled", origin.getId(), null, RequestContext.none());

        assertThat(chainOf(restarted)).extracting(Task::getTaskDefinition).containsExactly(
                TaskDefinition.AWS_SERVICE_APPLY_V1.name(),   // 취소 당시 진행 task부터
                TaskDefinition.AWS_BDC_COMMON_PLAN_V1.name(),
                TaskDefinition.AWS_BDC_COMMON_APPLY_V1.name());
    }

    @Test
    void doneOriginIsRejected() {
        // DONE은 active_target 슬롯을 이미 해제해 유니크 제약 백스톱이 없다 — 이 명시 검사가 유일 방어선이다.
        Pipeline origin = seedTerminal("rst-done", PipelineStatus.DONE,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE);

        assertThatThrownBy(() -> restarter.restart("rst-done", origin.getId(), null, RequestContext.none()))
                .isInstanceOf(PipelineNotRestartableException.class);
        assertThat(pipelineRepository.count()).isEqualTo(1);   // 아무것도 만들지 않는다
    }

    @Test
    void liveOriginIsRejectedWithExplicitCode() {
        // 비종료 원본은 유니크 제약 백스톱(ALREADY_ACTIVE 409)도 있지만, 그보다 먼저 정확한 코드로 거절한다.
        Pipeline running = seedActive("rst-live");

        assertThatThrownBy(() -> restarter.restart("rst-live", running.getId(), null, RequestContext.none()))
                .isInstanceOf(PipelineNotRestartableException.class);

        running.setStatus(PipelineStatus.PENDING);
        pipelineRepository.save(running);
        assertThatThrownBy(() -> restarter.restart("rst-live", running.getId(), null, RequestContext.none()))
                .isInstanceOf(PipelineNotRestartableException.class);
    }

    @Test
    void staleTerminalOriginIsRejected() {
        Pipeline older = seedTerminal("rst-stale", PipelineStatus.FAILED,
                TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);
        clock.advance(Duration.ofMinutes(5));
        seedTerminal("rst-stale", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);

        assertThatThrownBy(() -> restarter.restart("rst-stale", older.getId(), null, RequestContext.none()))
                .isInstanceOf(PipelineNotLatestException.class);
    }

    @Test
    void activeRunBlocksRestartAtTheUniqueConstraint() {
        // 경합 재현: 최신 검증(createdAt desc)은 통과하지만 삽입 시점에 활성 실행이 존재하는 상태.
        // 활성 rival을 원본보다 이른 createdAt으로 만들어 "검사 후 삽입 전 create가 끼어든" 순간을 고정한다.
        clock.advance(Duration.ofMinutes(10));
        Pipeline origin = seedTerminal("rst-race", PipelineStatus.FAILED,
                TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);
        clock.set(START);
        seedActive("rst-race");
        clock.advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> restarter.restart("rst-race", origin.getId(), null, RequestContext.none()))
                .isInstanceOf(PipelineAlreadyActiveException.class);   // 최종 심판은 active_target 유니크 제약
    }

    @Test
    void unknownTaskDefinitionIsRejected() {
        Pipeline origin = seedTerminal("rst-unknown", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
        Task failed = chainOf(origin).get(2);
        failed.setTaskDefinition("GONE_TASK_V0");   // 카탈로그에서 사라진 이름 — 조용한 열화 금지
        taskRepository.save(failed);

        assertThatThrownBy(() -> restarter.restart("rst-unknown", origin.getId(), null, RequestContext.none()))
                .isInstanceOf(UnknownTaskException.class);
    }

    @Test
    void fromSequenceOverridesOnlyTowardTheFront() {
        Pipeline origin = seedTerminal("rst-seq", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);

        assertThatThrownBy(() -> restarter.restart("rst-seq", origin.getId(), 3, RequestContext.none()))   // 실패 task 건너뛰기
                .isInstanceOf(InvalidResumeSequenceException.class);
        assertThatThrownBy(() -> restarter.restart("rst-seq", origin.getId(), -1, RequestContext.none()))
                .isInstanceOf(InvalidResumeSequenceException.class);

        Pipeline fullRerun = restarter.restart("rst-seq", origin.getId(), 0, RequestContext.none());   // DONE 재실행은 멱등-안전(결정 3)

        assertThat(chainOf(fullRerun)).hasSize(4);
    }

    @Test
    void previewMatchesRestartAndSavesNothing() {
        Pipeline origin = seedTerminal("rst-preview", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
        List<Task> originChain = chainOf(origin);
        long pipelineCount = pipelineRepository.count();

        RestartPreview preview = controller.restartPreview("rst-preview", origin.getId(), null);

        assertThat(preview.resumeFromSequence()).isEqualTo(2);
        assertThat(preview.origin().status()).isEqualTo(PipelineStatus.FAILED);
        assertThat(preview.origin().totalTaskCount()).isEqualTo(4);
        assertThat(preview.origin().doneTaskCount()).isEqualTo(2);
        assertThat(preview.skippedTasks()).extracting(RestartPreview.SkippedTask::sequence).containsExactly(0, 1);
        assertThat(preview.tasksToRun())
                .extracting(RestartPreview.TaskToRun::sequence, RestartPreview.TaskToRun::originTaskId,
                        RestartPreview.TaskToRun::originStatus, RestartPreview.TaskToRun::originErrorCode)
                .containsExactly(
                        tuple(2, originChain.get(2).getId(), TaskStatus.FAILED, ErrorCode.JOB_FAILED),
                        tuple(3, originChain.get(3).getId(), TaskStatus.CANCELLED, null));
        assertThat(preview.warnings()).isNotEmpty();   // 방금 끝난 원본 — in-flight job 안내
        assertThat(pipelineRepository.count()).isEqualTo(pipelineCount);   // 읽기 전용

        // 실행과 검증을 공유한다 — 불가 상태는 preview부터 409.
        Pipeline done = seedTerminal("rst-preview-done", PipelineStatus.DONE,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE);
        assertThatThrownBy(() -> controller.restartPreview("rst-preview-done", done.getId(), null))
                .isInstanceOf(PipelineNotRestartableException.class);
        assertThatThrownBy(() -> controller.restartPreview("rst-preview", done.getId(), null))
                .isInstanceOf(PipelineNotFoundException.class);   // target 불일치는 404
    }

    @Test
    void detailCarriesProvenanceInBothDirections() {
        Pipeline origin = seedTerminal("rst-links", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);

        PipelineDetail restarted = controller.restart("rst-links", origin.getId(),
                new RestartPipelineRequest(null, null, null));

        assertThat(restarted.originPipelineId()).isEqualTo(origin.getId());
        assertThat(restarted.origin().pipelineId()).isEqualTo(origin.getId());
        assertThat(restarted.origin().totalTaskCount()).isEqualTo(4);
        assertThat(restarted.origin().doneTaskCount()).isEqualTo(2);
        assertThat(restarted.origin().resumedFromSequence()).isEqualTo(2);   // 원본 4단계 중 2번부터
        assertThat(restarted.tasks()).extracting(TaskSummary::originTaskId).doesNotContainNull();
        assertThat(restarted.doneTaskCount()).isZero();   // 진행률은 자기 suffix 기준(숫자 조작 없음)

        PipelineDetail originDetail = queryService.detail(origin.getId());
        assertThat(originDetail.restartedByPipelineId()).isEqualTo(restarted.pipelineId());   // 역링크
        assertThat(originDetail.origin()).isNull();
    }

    @Test
    void restartOfRestartChainsToImmediateOrigin() {
        Pipeline first = seedTerminal("rst-chain", PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
        clock.advance(Duration.ofMinutes(5));
        Pipeline second = restarter.restart("rst-chain", first.getId(), null, RequestContext.none());
        terminalize(second, PipelineStatus.FAILED, TaskStatus.FAILED, TaskStatus.CANCELLED);
        clock.advance(Duration.ofMinutes(5));

        Pipeline third = restarter.restart("rst-chain", second.getId(), null, RequestContext.none());

        assertThat(third.getOriginPipelineId()).isEqualTo(second.getId());   // 체인은 직전 실행을 가리킨다
        assertThatThrownBy(() -> restarter.restart("rst-chain", first.getId(), null, RequestContext.none()))
                .isInstanceOf(PipelineNotLatestException.class);   // 최신 가드가 체인 끝만 허용
    }

    /**
     * 만료된 승인 게이트에서 재시작하면 지점이 그 앞의 Plan까지 당겨진다(승인 게이트 ADR §결정 3).
     * 게이트만 다시 돌리면 새 실행에 Plan 단계가 없어 승인자에게 보여줄 결과가 존재하지 않는다 — 근거 없이
     * 버튼만 보게 되는 셈이라 "승인만 다시 하기"는 허용하지 않는다.
     */
    @Test
    void restartFromAnExpiredGatePullsBackToThePlanStep() {
        Pipeline origin = seedGatedTerminal("rst-gate");

        RestartPreview preview = restarter.preview("rst-gate", origin.getId(), null);

        assertThat(preview.resumeFromSequence()).isZero();   // 기본 지점은 게이트(1)였지만 Plan(0)까지 당겨진다
        assertThat(preview.warnings()).anySatisfy(warning -> assertThat(warning).contains("Plan 단계부터"));

        Pipeline restarted = restarter.restart("rst-gate", origin.getId(), null,
                RequestContext.of("admin@example.com", null));

        assertThat(chainOf(restarted)).extracting(Task::getTaskDefinition).containsExactly(
                TaskDefinition.AWS_SERVICE_PLAN_V1.name(),
                TaskDefinition.AWS_SERVICE_APPLY_APPROVAL_V1.name(),
                TaskDefinition.AWS_SERVICE_APPLY_V1.name());
        assertThat(restarted.getRequestedBy()).isEqualTo("admin@example.com");
    }

    /** 호출자가 게이트를 지점으로 콕 집어도 같은 이유로 당긴다 — "게이트만 재시작"이라는 결과는 만들지 않는다. */
    @Test
    void anExplicitGateSequenceIsPulledBackToo() {
        Pipeline origin = seedGatedTerminal("rst-gate-explicit");

        assertThat(restarter.preview("rst-gate-explicit", origin.getId(), 1).resumeFromSequence()).isZero();
    }

    /** 승인 단계가 있는 체인은 요청자가 필수라, 재시작이 요청 맥락을 비워 두면 원본 승계가 그 자리를 메운다. */
    @Test
    void aGatedRestartInheritsTheRequesterFromTheOrigin() {
        Pipeline origin = seedGatedTerminal("rst-gate-inherit", RequestContext.of("first-requester", null));

        Pipeline restarted = restarter.restart("rst-gate-inherit", origin.getId(), null, RequestContext.none());

        assertThat(restarted.getRequestedBy()).isEqualTo("first-requester");
    }

    /** 재시작 요청이 요청 맥락을 비워 두면 원본 실행에서 승계한다 — 재시작했다고 요청자가 사라지지 않는다. */
    @Test
    void anEmptyRequestContextIsInheritedFromTheOrigin() {
        // 요청자는 write-once 컬럼이라 생성 시점에만 채워진다 — 원본을 그렇게 만들어 둔다.
        Pipeline origin = seedTerminal("rst-inherit", RequestContext.of("first-requester", "먼저 요청한 건"),
                PipelineStatus.FAILED, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED,
                TaskStatus.CANCELLED);

        Pipeline restarted = restarter.restart("rst-inherit", origin.getId(), null, RequestContext.none());

        assertThat(restarted.getRequestedBy()).isEqualTo("first-requester");
        assertThat(restarted.getRequestNote()).isEqualTo("먼저 요청한 건");
    }

    /** 재시작 요청이 자기 요청자를 실어 보내면 그 사람이 새 요청자다 — 승계는 비어 있을 때만이다. */
    @Test
    void anExplicitRequestContextWinsOverTheOrigin() {
        Pipeline origin = seedTerminal("rst-override", RequestContext.of("first-requester", "먼저 요청한 건"),
                PipelineStatus.FAILED, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED,
                TaskStatus.CANCELLED);

        Pipeline restarted = restarter.restart("rst-override", origin.getId(), null,
                RequestContext.of("second-requester", null));

        assertThat(restarted.getRequestedBy()).isEqualTo("second-requester");
        assertThat(restarted.getRequestNote()).isNull();   // 실어 보낸 맥락이 통째로 이긴다(필드별 병합이 아니다)
    }

    /** 승인 만료로 끝난 실행 시딩 — Plan은 성공했고 게이트에서 실패했다. */
    private Pipeline seedGatedTerminal(String target) {
        return seedGatedTerminal(target, RequestContext.none());
    }

    private Pipeline seedGatedTerminal(String target, RequestContext request) {
        List<PlannedStep> steps = List.of(
                new PlannedStep(TaskDefinition.AWS_SERVICE_PLAN_V1, null),
                new PlannedStep(TaskDefinition.AWS_SERVICE_APPLY_APPROVAL_V1, null),
                new PlannedStep(TaskDefinition.AWS_SERVICE_APPLY_V1, null));
        Pipeline pipeline = inserter.insert(new PipelinePlan(target, PipelineType.INSTALL, CloudProvider.AWS,
                "AWS_INSTALL_WITH_ADMIN_CONSENT_V1", request, null, steps));
        terminalize(pipeline, PipelineStatus.FAILED,
                TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
        return pipeline;
    }

    /** 원본 시딩: INSTALL 분류의 4-task 실행을 삽입한 뒤 주어진 상태로 종료시킨다. */
    private Pipeline seedTerminal(String target, PipelineStatus status, TaskStatus... taskStatuses) {
        return seedTerminal(target, RequestContext.none(), status, taskStatuses);
    }

    /** 요청 맥락을 지정해 시딩한다 — 승계 검증은 원본이 요청자를 들고 있어야 성립한다. */
    private Pipeline seedTerminal(String target, RequestContext request, PipelineStatus status,
            TaskStatus... taskStatuses) {
        Pipeline pipeline = seedActive(target, request);
        terminalize(pipeline, status, taskStatuses);
        return pipeline;
    }

    /** 활성(RUNNING, active_target 점유) 실행 삽입 — startDelay 0이라 fast path RUNNING이다. */
    private Pipeline seedActive(String target) {
        return seedActive(target, RequestContext.none());
    }

    private Pipeline seedActive(String target, RequestContext request) {
        List<PlannedStep> steps = CHAIN_DEFINITIONS.stream()
                .map(definition -> new PlannedStep(definition, null))
                .toList();
        return inserter.insert(new PipelinePlan(target, PipelineType.INSTALL, CloudProvider.AWS,
                "AWS_INSTALL_V1", request, null, steps));
    }

    /** 행 직접 갱신으로 종료 상태를 만든다 — 실행 엔진 없이 write-back 결과와 같은 꼴(activeTarget 해제 포함). */
    private void terminalize(Pipeline pipeline, PipelineStatus status, TaskStatus... taskStatuses) {
        List<Task> chain = chainOf(pipeline);
        for (int i = 0; i < taskStatuses.length; i++) {
            Task task = chain.get(i);
            task.setStatus(taskStatuses[i]);
            if (taskStatuses[i] == TaskStatus.FAILED) {
                task.setFailCount(3);
                task.setErrorCode(ErrorCode.JOB_FAILED);
            }
            taskRepository.save(task);
        }
        pipeline.setStatus(status);
        pipeline.setActiveTarget(null);
        pipeline.setLastActivityAt(clock.instant());
        pipelineRepository.save(pipeline);
    }

    private List<Task> chainOf(Pipeline pipeline) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).maxTerraformPollCallErrors(10)
                    .startDelay(Duration.ZERO).build();
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
