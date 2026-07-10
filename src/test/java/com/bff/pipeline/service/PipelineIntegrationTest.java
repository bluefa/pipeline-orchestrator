package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.controller.TargetSourcePipelineController;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.pipeline.CreatePipelineRequest;
import com.bff.pipeline.dto.pipeline.CustomPipelineRequest;
import com.bff.pipeline.dto.pipeline.CustomTaskRequest;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.TaskSummary;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
import com.bff.pipeline.service.task.ConditionCheckTask;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskStateMachine;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.terraform.TerraformJobStateRecorder;
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
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
 * LIN-24 [A4] Backend 통합 검증. A1(시작 지연, LIN-17)·A2(custom recipe, LIN-18)·A3(Admin 조회 API, LIN-19)를
 * 각각이 아니라 합쳐진 상태로 한 흐름에서 검증한다. 개별 슬라이스는 각 이슈의 테스트가 이미 덮으므로, 여기서는
 * 그 셋의 seam — custom recipe가 시작 지연 창(PENDING)에 걸린 채 Admin 조회 API로 관측되고, 대기 중 취소가 조회에
 * 반영되며, 그 와중에도 ADR-016 one-active-per-target이 유지되고, 기존 고정 recipe 흐름이 회귀 없이 도는지 — 만 본다.
 *
 * 따라서 상태 관측은 엔티티 reload가 아니라 언제나 {@link PipelineQueryService}(=Admin 조회 read path)를 통한다.
 * 생성/취소 명령은 실제 실행 wiring(claim-pull worker + lifecycle)을 그대로 태우고, {@link MutableClock}으로 지연
 * 경과·due-ness를 결정적으로 제어한다. {@code NOT_SUPPORTED}로 테스트 래핑 tx를 억제해 각 명령이 프로덕션처럼 독립
 * 커밋하고 조회가 커밋된 상태를 읽게 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class,
        ConditionCheckTask.class, ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class,
        PipelineInserter.class, PipelineControl.class, RecipeCatalog.class, PipelineQueryService.class,
        TargetSourcePipelineController.class, PipelineIntegrationTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineIntegrationTest {

    private static final Instant START = Instant.parse("2026-07-04T00:00:00Z");
    private static final Duration DELAY = Duration.ofSeconds(15);   // LIN-17 요구값 그대로(15초 시작 지연)

    @Autowired private TargetSourcePipelineController controller;   // Admin 실행 엔드포인트(P10 create / custom)
    @Autowired private PipelineQueryService service;               // Admin 조회 read path(P4 detail / P1 live)
    @Autowired private PipelineControl control;                     // 취소 명령(P6)
    @Autowired private PipelineWorker pipelineWorker;               // claim-pull 실행 구동
    @Autowired private FakeInfraManagerClient infraManagerClient;
    @Autowired private MutableClock clock;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private TaskCheckRepository taskCheckRepository;
    @Autowired private TerraformResultRepository terraformResultRepository;
    @Autowired private TerraformJobStateRepository terraformJobStateRepository;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManagerClient.onCloudProvider(CloudProvider.AWS);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        infraManagerClient.onPoll(() -> TerraformPoll.running("RUNNING"));
        infraManagerClient.onCheck(() -> false);
        infraManagerClient.onResult(() -> "terraform: ok");
    }

    @AfterEach
    void clean() {
        // 자식 테이블부터 지운다(task_check→task_attempt, terraform_result→task) — 비-tx 공유 H2에 잔여물을 남기지 않는다
        taskCheckRepository.deleteAll();
        terraformResultRepository.deleteAll();
        terraformJobStateRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    /**
     * 교차 시나리오 1 — custom recipe 생성 → 15초 지연 적용 → Admin 조회에서 CUSTOM + task 설명이 보이고,
     * 지연 경과 후 요청 순서대로 실행이 시작된다. (A2 분류 + A1 지연 + A3 조회의 seam)
     */
    @Test
    void customRecipeUnderStartDelay_isQueriedAsCustomWithDescriptions_thenRunsAfterTheDelay() {
        PipelineDetail created = controller.createCustom("integ-custom", new CustomPipelineRequest(List.of(
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "plan the service"),
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply the service"))));
        long pipelineId = created.pipelineId();

        // 생성 응답(P10) 자체가 CUSTOM 분류 + 설명 + 초기 PENDING (지연이 실제로 걸렸는지는 아래 pollOnce로 확증)
        assertThat(created.type()).isEqualTo(PipelineType.CUSTOM);
        assertThat(created.recipeDefinition()).isNull();
        assertThat(created.status()).isEqualTo(PipelineStatus.PENDING);

        // 조회 read path로도 동일 관측 — CUSTOM / PENDING / task별 설명 / 진행 0
        PipelineDetail pending = service.detail(pipelineId);
        assertThat(pending.type()).isEqualTo(PipelineType.CUSTOM);
        assertThat(pending.recipeDefinition()).isNull();
        assertThat(pending.status()).isEqualTo(PipelineStatus.PENDING);
        assertThat(pending.doneTaskCount()).isZero();
        assertThat(pending.tasks()).extracting(TaskSummary::taskDefinition, TaskSummary::description).containsExactly(
                tuple(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "plan the service"),
                tuple(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply the service"));

        // 지연 창 동안 PENDING 통계에 잡히고 RUNNING엔 안 잡힌다(LIN-30 구분)
        assertThat(service.liveStatistics().pendingPipelineCount()).isEqualTo(1);
        assertThat(service.liveStatistics().runningPipelineCount()).isZero();

        // 시작 지연이 실제로 걸려 있음을 확증 — 경과 전엔 어떤 task도 dispatch되지 않는다(지연이 0이면 여기서 dispatch됨)
        assertThat(pipelineWorker.pollOnce()).isEmpty();
        assertThat(service.detail(pipelineId).status()).isEqualTo(PipelineStatus.PENDING);

        // 지연 경과 → 첫 claim이 PENDING→RUNNING 전이 + 요청 첫 task dispatch
        clock.set(START.plus(DELAY));
        pipelineWorker.pollOnce();

        PipelineDetail running = service.detail(pipelineId);
        assertThat(running.status()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(running.type()).isEqualTo(PipelineType.CUSTOM);      // 분류는 실행 후에도 유지
        assertThat(running.currentTaskSequence()).isZero();             // 요청 순서 첫 task 실행 중
        assertThat(running.tasks().getFirst().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(running.tasks().getFirst().description()).isEqualTo("plan the service");   // 설명은 실행 후에도 보존
    }

    /**
     * 교차 시나리오 2 — 지연 대기 중 취소하면 어떤 task도 실행되지 않고, Admin 조회가 취소 상태로 관측된다.
     * (A1 대기 창 + A3 조회에 반영되는 취소 seam)
     */
    @Test
    void cancelDuringTheStartDelayWait_isQueriedAsCancelled_andDispatchesNoTask() {
        PipelineDetail created = controller.createCustom("integ-cancel", new CustomPipelineRequest(List.of(
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "plan"),
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply"))));
        long pipelineId = created.pipelineId();
        assertThat(created.status()).isEqualTo(PipelineStatus.PENDING);

        // 대기(unclaimed) 중 취소 → Case A 즉시 종단
        control.cancel(pipelineId);

        // Admin 조회로 취소 확인 — pipeline CANCELLED, 전 task CANCELLED, 진행 0
        PipelineDetail cancelled = service.detail(pipelineId);
        assertThat(cancelled.status()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(cancelled.type()).isEqualTo(PipelineType.CUSTOM);
        assertThat(cancelled.doneTaskCount()).isZero();
        assertThat(cancelled.tasks()).extracting(TaskSummary::status).containsOnly(TaskStatus.CANCELLED);

        // 지연이 경과해도 종단이라 아무것도 dispatch되지 않는다
        clock.set(START.plus(DELAY));
        assertThat(pipelineWorker.pollOnce()).isEmpty();
        assertThat(service.detail(pipelineId).tasks())
                .noneMatch(task -> task.status() == TaskStatus.IN_PROGRESS || task.status() == TaskStatus.DONE);
    }

    /**
     * 교차 시나리오 3 — custom 파이프라인이 지연 대기(PENDING·비종단)로 target 슬롯을 점유하는 동안 동일 target
     * 재실행은 409로 거절되고, 취소로 슬롯이 풀리면 다시 실행할 수 있다. (ADR-016 one-active-per-target 유지)
     *
     * 두 번째 실행은 정상 카탈로그 요청이라 유일한 거절 사유가 활성 슬롯 뿐이다 — 이름/provider 검증 실패가 아니라
     * 유일성 가드가 막았음을 단언이 실제로 증명한다.
     */
    @Test
    void oneActivePerTarget_holdsWhileACustomPipelineWaits_andReleasesAfterCancel() {
        String target = "integ-unique";
        PipelineDetail first = controller.createCustom(target, new CustomPipelineRequest(List.of(
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "plan"))));
        assertThat(first.status()).isEqualTo(PipelineStatus.PENDING);   // 비종단 → active_target 점유

        // 대기 중에도 동일 target 재실행은 거절(정상 요청이므로 원인은 활성 슬롯 뿐)
        assertThatThrownBy(() -> controller.create(target, new CreatePipelineRequest(PipelineType.INSTALL)))
                .isInstanceOf(PipelineAlreadyActiveException.class);

        // 취소로 슬롯 해제 → 조회도 CANCELLED
        control.cancel(first.pipelineId());
        assertThat(service.detail(first.pipelineId()).status()).isEqualTo(PipelineStatus.CANCELLED);

        // 해제 후엔 동일 target 새 실행 허용(다른 id)
        PipelineDetail second = controller.create(target, new CreatePipelineRequest(PipelineType.INSTALL));
        assertThat(second.pipelineId()).isNotEqualTo(first.pipelineId());
        assertThat(second.status()).isEqualTo(PipelineStatus.PENDING);
    }

    /**
     * 회귀 — 기존 고정 카탈로그 recipe(INSTALL)가 지연/custom 추가 뒤에도 그대로 돈다. 지연은 고정 recipe에도 동일하게
     * 적용(PENDING)되고, 경과 후 정상 구동해 DONE에 이르며 조회 내내 type=INSTALL·recipe_definition 백킹을 유지한다.
     */
    @Test
    void aFixedCatalogRecipe_stillCreatesAsInstallUnderTheStartDelay_andRunsToDone() {
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
        infraManagerClient.onCheck(() -> true);

        PipelineDetail created = controller.create("integ-fixed", new CreatePipelineRequest(PipelineType.INSTALL));
        long pipelineId = created.pipelineId();
        assertThat(created.type()).isEqualTo(PipelineType.INSTALL);
        assertThat(created.recipeDefinition()).isEqualTo("AWS_INSTALL_V1");   // 고정 카탈로그 recipe 백킹
        assertThat(created.status()).isEqualTo(PipelineStatus.PENDING);       // 고정 recipe도 생성 직후 PENDING

        // 시작 지연이 고정 recipe에도 동일 적용됨을 확증 — 경과 전엔 미실행
        assertThat(pipelineWorker.pollOnce()).isEmpty();

        // 지연 경과 후 정상 구동 → DONE (fake가 매 poll 즉시 성공하므로 clock을 진행시키지 않는 happy-path 구동)
        clock.set(START.plus(DELAY));
        runToTerminalWithoutAdvancingClock(pipelineId);

        PipelineDetail done = service.detail(pipelineId);
        assertThat(done.status()).isEqualTo(PipelineStatus.DONE);
        assertThat(done.type()).isEqualTo(PipelineType.INSTALL);
        assertThat(done.doneTaskCount()).isEqualTo(done.totalTaskCount());   // 전 task 완주
    }

    /**
     * happy-path 구동: fake가 매 poll 즉시 성공/충족을 돌려주므로 어떤 job도 turn을 넘겨 in-progress로 남지 않는다 —
     * clock을 진행시키지 않아야 executionTimeout에 걸리지 않는다(dispatch→poll→promote가 각각 한 turn).
     */
    private void runToTerminalWithoutAdvancingClock(long pipelineId) {
        for (int i = 0; i < 50 && !service.detail(pipelineId).status().isTerminal(); i++) {
            pipelineWorker.pollOnce();
        }
        assertThat(service.detail(pipelineId).status().isTerminal())
                .as("pipeline did not reach a terminal state within 50 polls")
                .isTrue();
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
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).startDelay(DELAY).build();
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
