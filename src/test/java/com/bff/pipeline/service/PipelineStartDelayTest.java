package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
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
 * LIN-17: 파이프라인 생성 후 {@code pipeline.start-delay} 만큼 대기한 뒤에야 첫 Task가 dispatch되는지 검증한다.
 * 지연은 sleep이 아니라 {@code nextDueAt = now + startDelay} 시딩으로 구현되므로, claim 술어
 * ({@code next_due_at <= now})가 지연 경과 전에는 이 실행을 잡지 않는다. 대기 중 취소도 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class, ConditionCheckTask.class,
        ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class, PipelineInserter.class,
        PipelineControl.class, RecipeCatalog.class, PipelineStartDelayTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineStartDelayTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");
    private static final Duration DELAY = Duration.ofSeconds(15);
    private static final Duration LEASE = Duration.ofSeconds(30);   // Wiring의 leaseDuration과 일치

    @Autowired private PipelineClaimer pipelineClaimer;
    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TerraformJobStateRepository terraformJobStateRepository;
    @Autowired private MutableClock clock;

    @AfterEach
    void clean() {
        terraformJobStateRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        clock.set(START);
    }

    @Test
    void firstTaskDoesNotDispatchBeforeTheDelayElapses() {
        Pipeline pipeline = creator.create("delay-a", PipelineType.DELETE);

        assertThat(pipeline.getStatus()).isEqualTo(PipelineStatus.PENDING);   // LIN-30: 지연 창 동안은 PENDING(RUNNING 아님)
        assertThat(pipeline.getNextDueAt()).isEqualTo(START.plus(DELAY));
        assertThat(pipelineClaimer.claimOneDue()).isEmpty();       // not due yet
        assertThat(pipelineWorker.pollOnce()).isEmpty();            // nothing to run
        assertThat(firstTask(pipeline).getStatus()).isEqualTo(TaskStatus.READY);   // still not dispatched
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.PENDING);   // 스캔 후에도 여전히 PENDING
    }

    @Test
    void firstTaskDispatchesAndTransitionsToRunningOnceTheDelayElapses() {
        Pipeline pipeline = creator.create("delay-b", PipelineType.DELETE);

        clock.set(START.plus(DELAY));
        pipelineWorker.pollOnce();   // now due → claim transitions PENDING→RUNNING, then dispatch first destroy

        assertThat(firstTask(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.RUNNING);   // LIN-30: 첫 claim에서 전이
    }

    @Test
    void claimTransitionKeepsTheNextDueAtSeedAndDoesNotResetItToNow() {
        Pipeline pipeline = creator.create("delay-d", PipelineType.DELETE);
        Instant seededDue = pipeline.getNextDueAt();   // START + DELAY

        clock.set(START.plus(DELAY).plus(Duration.ofSeconds(5)));   // due, and now strictly after the seed
        assertThat(pipelineClaimer.claimOneDue()).isPresent();      // claim transitions PENDING→RUNNING

        Pipeline claimed = reload(pipeline);
        assertThat(claimed.getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(claimed.getNextDueAt()).isEqualTo(seededDue);    // LIN-30: 전이가 next_due_at을 now로 덮지 않는다(seed 유지)
    }

    @Test
    void crashAfterTransitionResumesTheFirstTaskOnReclaim() {
        Pipeline pipeline = creator.create("delay-e", PipelineType.DELETE);

        clock.set(START.plus(DELAY));
        assertThat(pipelineClaimer.claimOneDue()).isPresent();   // PENDING→RUNNING + lease, 워커는 첫 task 실행 전 "크래시"
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(firstTask(pipeline).getStatus()).isEqualTo(TaskStatus.READY);   // not dispatched yet

        clock.set(START.plus(DELAY).plus(LEASE).plus(Duration.ofSeconds(1)));   // lease 만료
        pipelineWorker.pollOnce();   // 만료된 lease reclaim → 첫 task dispatch

        assertThat(firstTask(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);   // PENDING에 멈추지도, RUNNING서 유실도 안 됨
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.RUNNING);
    }

    @Test
    void cancelAfterClaimFallsToCooperativeCaseBNotTerminalResurrection() {
        Pipeline pipeline = creator.create("delay-f", PipelineType.DELETE);

        clock.set(START.plus(DELAY));
        assertThat(pipelineClaimer.claimOneDue()).isPresent();   // PENDING→RUNNING + live lease
        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.RUNNING);

        control.cancel(pipeline.getId());   // live lease → cancelIfIdle 0행 → Case B(cancel_requested만, status 불변)

        Pipeline afterCancel = reload(pipeline);
        assertThat(afterCancel.getStatus()).isEqualTo(PipelineStatus.RUNNING);   // 아직 종단 아님 — 워커가 안전지점에서 적용
        assertThat(afterCancel.isCancelRequested()).isTrue();                    // 협조적 취소 플래그
    }

    @Test
    void cancelDuringTheWaitRunsNoTaskAtAll() {
        Pipeline pipeline = creator.create("delay-c", PipelineType.DELETE);
        assertThat(pipeline.getStatus()).isEqualTo(PipelineStatus.PENDING);   // 대기(PENDING) 상태에서 취소

        control.cancel(pipeline.getId());   // still waiting, unclaimed → Case A cancels immediately

        assertThat(reload(pipeline).getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(tasks(pipeline)).allSatisfy(task ->
                assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED));

        clock.set(START.plus(DELAY));
        assertThat(pipelineWorker.pollOnce()).isEmpty();   // terminal → nothing dispatches after the delay either
        assertThat(tasks(pipeline)).noneSatisfy(task ->
                assertThat(task.getStatus()).isIn(TaskStatus.IN_PROGRESS, TaskStatus.DONE));
    }

    @Test
    void pendingPipelineBlocksASecondCreateForTheSameTarget() {
        Pipeline pending = creator.create("delay-g", PipelineType.DELETE);
        assertThat(pending.getStatus()).isEqualTo(PipelineStatus.PENDING);

        // PENDING도 비종단이라 active_target 슬롯을 점유한다 → 중복 create는 409로 거절(one-active-per-target)
        assertThatThrownBy(() -> creator.create("delay-g", PipelineType.DELETE))
                .isInstanceOf(PipelineAlreadyActiveException.class);
    }

    @Test
    void pendingAndRunningCountsMoveExactlyOnTransition() {
        creator.create("delay-h", PipelineType.DELETE);

        assertThat(pipelineRepository.countByStatus(PipelineStatus.PENDING)).isEqualTo(1);
        assertThat(pipelineRepository.countByStatus(PipelineStatus.RUNNING)).isZero();

        clock.set(START.plus(DELAY));
        assertThat(pipelineClaimer.claimOneDue()).isPresent();   // PENDING→RUNNING

        assertThat(pipelineRepository.countByStatus(PipelineStatus.PENDING)).isZero();
        assertThat(pipelineRepository.countByStatus(PipelineStatus.RUNNING)).isEqualTo(1);
    }

    private Pipeline reload(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow();
    }

    private Task firstTask(Pipeline pipeline) {
        return tasks(pipeline).getFirst();
    }

    private List<Task> tasks(Pipeline pipeline) {
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
                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).maxTerraformPollCallErrors(10).startDelay(DELAY).build();
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
