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
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, ConditionCheckTask.class,
        ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class, PipelineInserter.class,
        PipelineControl.class, RecipeCatalog.class, PipelineStartDelayTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineStartDelayTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");
    private static final Duration DELAY = Duration.ofSeconds(15);

    @Autowired private PipelineClaimer pipelineClaimer;
    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private MutableClock clock;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        clock.set(START);
    }

    @Test
    void firstTaskDoesNotDispatchBeforeTheDelayElapses() {
        Pipeline pipeline = creator.create("delay-a", PipelineType.DELETE);

        assertThat(pipeline.getNextDueAt()).isEqualTo(START.plus(DELAY));
        assertThat(pipelineClaimer.claimOneDue()).isEmpty();       // not due yet
        assertThat(pipelineWorker.pollOnce()).isEmpty();            // nothing to run
        assertThat(firstTask(pipeline).getStatus()).isEqualTo(TaskStatus.READY);   // still not dispatched
    }

    @Test
    void firstTaskDispatchesOnceTheDelayElapses() {
        Pipeline pipeline = creator.create("delay-b", PipelineType.DELETE);

        clock.set(START.plus(DELAY));
        pipelineWorker.pollOnce();   // now due → dispatch first destroy

        assertThat(firstTask(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void cancelDuringTheWaitRunsNoTaskAtAll() {
        Pipeline pipeline = creator.create("delay-c", PipelineType.DELETE);

        control.cancel(pipeline.getId());   // still waiting, unclaimed → Case A cancels immediately

        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
        assertThat(tasks(pipeline)).allSatisfy(task ->
                assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED));

        clock.set(START.plus(DELAY));
        assertThat(pipelineWorker.pollOnce()).isEmpty();   // terminal → nothing dispatches after the delay either
        assertThat(tasks(pipeline)).noneSatisfy(task ->
                assertThat(task.getStatus()).isIn(TaskStatus.IN_PROGRESS, TaskStatus.DONE));
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
