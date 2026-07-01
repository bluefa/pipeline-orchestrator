package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
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
import com.bff.pipeline.service.task.terraform.TerraformTask;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관찰(observation) 전용 테이블의 쓰기 동작을 검증한다(ADR-021 워커가 구동). 시도마다 올바른 {@code attempt_no}를 가진
 * {@code task_attempt} 행이 하나씩 기록되고, {@code task_check}는 폴링마다 새 행을 추가하지 않고 attempt당 한 행을
 * 제자리 갱신함을 확인한다. {@link PipelineExecutionTest.Wiring}(fake InfraManager, MutableClock, settings)을 재사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, ConditionCheckTask.class,
        ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class, PipelineInserter.class,
        RecipeCatalog.class, PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ObservationTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private TaskCheckRepository taskCheckRepository;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManagerClient;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        infraManagerClient.onPoll(TerraformPoll::running);
        infraManagerClient.onCheck(() -> false);
    }

    @AfterEach
    void clean() {
        taskCheckRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void aHappyTerraformTaskRecordsOneDoneAttemptWithItsResponse() {
        Pipeline pipeline = creator.create("obs-happy", PipelineType.DELETE);
        infraManagerClient.onPoll(TerraformPoll::success);
        pipelineWorker.pollOnce();
        pipelineWorker.pollOnce();

        var recorded = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));
        assertThat(recorded).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNumber()).isEqualTo(1);
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(attempt.getResponse()).isEqualTo("[\"job-1\"]");
        });
        assertThat(taskCheckRepository.findByTaskAttemptId(recorded.getFirst().getId())).isEmpty();
    }

    @Test
    void aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo() {
        Pipeline pipeline = creator.create("obs-retry", PipelineType.DELETE);
        infraManagerClient.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        var recorded = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));
        assertThat(recorded).extracting(TaskAttempt::getAttemptNumber).containsExactly(1, 2);
        assertThat(recorded).allSatisfy(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(attempt.getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        });
    }

    @Test
    void aConditionPolledNotMetUpdatesOneCheckRowInPlace() {
        Pipeline pipeline = createInstallAtConditionInProgress();
        Long conditionTaskId = taskId(pipeline, 1);

        for (int i = 0; i < 3; i++) {
            pipelineWorker.pollOnce();
            clock.advance(Duration.ofMinutes(11));
        }

        TaskAttempt attempt = taskAttemptRepository.findByTaskIdAndAttemptNumber(conditionTaskId, 1).orElseThrow();
        assertThat(taskCheckRepository.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(3);
            assertThat(check.getNotMetCount()).isEqualTo(3);
        });
        assertThat(taskCheckRepository.findAll()).hasSize(1);
    }

    private Pipeline createInstallAtConditionInProgress() {
        Pipeline pipeline = creator.create("obs-cond", PipelineType.INSTALL);
        infraManagerClient.onPoll(TerraformPoll::success);
        pipelineWorker.pollOnce();   // dispatch terraform
        pipelineWorker.pollOnce();   // poll terraform → DONE + promote condition READY
        pipelineWorker.pollOnce();   // dispatch condition → IN_PROGRESS
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(1).getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20; i++) {
            pipelineWorker.pollOnce();
            clock.advance(Duration.ofHours(1));
            if (pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus() != PipelineStatus.RUNNING) {
                return;
            }
        }
    }

    private Long taskId(Pipeline pipeline, int sequence) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(sequence).getId();
    }
}
