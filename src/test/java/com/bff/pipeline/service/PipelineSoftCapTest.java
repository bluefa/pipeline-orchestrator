package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
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
 * ADR-021 Decision 7 soft caps. {@code runningPipelineCap=1}로 claim 게이트를, {@code terraformSlotCap=0}으로 TF slot
 * 게이트를 강제한다. 두 캡 모두 soft이며 생성은 게이트하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class, ConditionCheckTask.class,
        ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class, PipelineInserter.class,
        RecipeCatalog.class, PipelineSoftCapTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineSoftCapTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineClaimer pipelineClaimer;
    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineCreator creator;
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
    void theAdmissionCapBlocksASecondConcurrentClaim() {
        creator.create("cap-a", PipelineType.DELETE);
        creator.create("cap-b", PipelineType.DELETE);

        assertThat(pipelineClaimer.claimOneDue()).isPresent();   // 1 active claim == cap
        assertThat(pipelineClaimer.claimOneDue()).isEmpty();      // blocked by the soft cap
    }

    @Test
    void creationIsNotGatedByTheCap() {
        creator.create("cap-c", PipelineType.DELETE);
        pipelineClaimer.claimOneDue();   // cap reached

        Pipeline overCap = creator.create("cap-d", PipelineType.DELETE);

        assertThat(pipelineRepository.findById(overCap.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.RUNNING);
    }

    @Test
    void zeroStartDelayTakesTheFastPathToRunningWithoutEverBeingPending() {
        Pipeline pipeline = creator.create("cap-fast", PipelineType.DELETE);

        // startDelay=0 → fast path: 생성 즉시 RUNNING, nextDueAt=now, PENDING을 거치지 않는다(LIN-30)
        assertThat(pipeline.getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(pipeline.getNextDueAt()).isEqualTo(START);
        assertThat(pipelineRepository.countByStatus(PipelineStatus.PENDING)).isZero();
        assertThat(pipelineClaimer.claimOneDue()).isPresent();   // 즉시 claim 가능
    }

    @Test
    void theTerraformSlotGateReschedulesAndReleasesTheClaimWhenNoSlotIsFree() {
        occupyTheOnlyTerraformSlot();
        Pipeline pipeline = creator.create("cap-slot", PipelineType.DELETE);   // the only due pipeline

        pipelineWorker.pollOnce();   // terraform READY dispatch, but the single slot is taken → reschedule

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst().getStatus())
                .isEqualTo(TaskStatus.READY);                       // not dispatched
        assertThat(after.getClaimedBy()).isNull();                  // claim released
        assertThat(after.getNextDueAt()).isEqualTo(START.plusSeconds(1));   // pushed by terraformSlotRetry
    }

    /** terraformSlotCap=1을 채우는 IN_PROGRESS terraform task를 두되, 그 pipeline은 미래 due로 두어 claim 대상에서 제외한다. */
    private void occupyTheOnlyTerraformSlot() {
        Pipeline filler = creator.create("cap-filler", PipelineType.DELETE);
        Task task = taskRepository.findByPipelineIdOrderBySequenceAsc(filler.getId()).getFirst();
        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.save(task);
        Pipeline row = pipelineRepository.findById(filler.getId()).orElseThrow();
        row.setNextDueAt(START.plus(Duration.ofDays(1)));   // not due → won't be claimed
        pipelineRepository.save(row);
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
                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).startDelay(Duration.ZERO).build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(1).terraformSlotCap(1).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }
    }
}
