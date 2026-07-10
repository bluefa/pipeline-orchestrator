package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@code task_attempt} 행이 하나씩 기록되고, {@code task_check}는 TERRAFORM_JOB 시도에서는 폴링마다 새 행을 추가하지
 * 않고 attempt당 한 행을 제자리 갱신하며(call_count 증가), CONDITION_CHECK에서는 폴 하나가 곧 시도 하나라
 * 폴마다 새 행이 하나씩 기록됨을 확인한다(ADR-016 §6). {@link PipelineExecutionTest.Wiring}(fake InfraManager,
 * MutableClock, settings)을 재사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class, ConditionCheckTask.class,
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
    @Autowired private TerraformJobStateRepository terraformJobStateRepository;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManagerClient;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        infraManagerClient.onPoll(() -> TerraformPoll.running("RUNNING"));
        infraManagerClient.onCheck(() -> false);
    }

    @AfterEach
    void clean() {
        taskCheckRepository.deleteAll();
        terraformJobStateRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void aHappyTerraformTaskRecordsOneDoneAttemptWithItsResponse() {
        Pipeline pipeline = creator.create("obs-happy", PipelineType.DELETE);
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
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
        infraManagerClient.onPoll(() -> TerraformPoll.failure("FAILED", null));

        runUntilTerminal(pipeline);

        var recorded = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));
        assertThat(recorded).extracting(TaskAttempt::getAttemptNumber).containsExactly(1, 2);
        assertThat(recorded).allSatisfy(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(attempt.getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        });
    }

    @Test
    void aTerraformPolledRunningUpdatesOneCheckRowInPlace() {
        Pipeline pipeline = creator.create("obs-tf-running", PipelineType.DELETE);
        infraManagerClient.onPoll(() -> TerraformPoll.running("RUNNING"));
        pipelineWorker.pollOnce();                  // dispatch terraform → IN_PROGRESS (attempt 1)
        for (int i = 0; i < 3; i++) {
            pipelineWorker.pollOnce();              // poll terraform → RUNNING, updated in place
            clock.advance(Duration.ofMinutes(11));  // < execution-timeout (PT50M), so still RUNNING
        }

        TaskAttempt attempt = taskAttemptRepository.findByTaskIdAndAttemptNumber(taskId(pipeline, 0), 1).orElseThrow();
        assertThat(taskCheckRepository.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(3);
            assertThat(check.getLastExternalStatus()).isEqualTo("RUNNING");
        });
        assertThat(taskCheckRepository.findAll()).hasSize(1);
    }

    @Test
    void aConditionNotMetRecordsOneCheckRowPerPollAcrossAttempts() {
        Pipeline pipeline = createInstallAtConditionInProgress();   // onCheck stays not-met (reset default)
        Long conditionTaskId = taskId(pipeline, 2);

        runUntilTerminal(pipeline);

        // maxFailCount = 2: two not-met polls, each its own attempt + its own check row.
        var attempts = taskAttemptRepository.findByTaskIdOrderByAttemptNumberAsc(conditionTaskId);
        assertThat(attempts).extracting(TaskAttempt::getAttemptNumber).containsExactly(1, 2);
        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(attempt.getErrorCode()).isEqualTo(ErrorCode.CONDITION_NOT_MET);
            // 타입 DTO가 버리는 detail 필드까지 원문 그대로 task_attempt.response에 실린다(full-body 관측)
            assertThat(attempt.getResponse()).isEqualTo("{\"met\":false,\"detail\":\"probe\"}");
            assertThat(taskCheckRepository.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
                assertThat(check.getCallCount()).isEqualTo(1);
                assertThat(check.getNotMetCount()).isEqualTo(1);
                assertThat(check.getLastExternalStatus()).isEqualTo("NOT_MET");
            });
        });
        // one row per condition poll (2); the terraform DONE attempts (plan·apply) record none.
        assertThat(taskCheckRepository.findAll()).hasSize(2);
    }

    @Test
    void aConditionMetRecordsResponseAndOneMetCheckThenDone() {
        Pipeline pipeline = createInstallAtConditionInProgress();   // condition IN_PROGRESS, attempt 1
        Long conditionTaskId = taskId(pipeline, 2);
        infraManagerClient.onCheck(() -> true);                     // the next poll observes MET

        pipelineWorker.pollOnce();                                  // poll condition met → DONE

        TaskAttempt attempt = taskAttemptRepository.findByTaskIdAndAttemptNumber(conditionTaskId, 1).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(attempt.getErrorCode()).isNull();
        // met 폴의 원문도 여분 필드 포함 full-body 그대로 기록된다
        assertThat(attempt.getResponse()).isEqualTo("{\"met\":true,\"detail\":\"probe\"}");
        assertThat(taskCheckRepository.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(1);
            assertThat(check.getLastExternalStatus()).isEqualTo("MET");
            assertThat(check.getNotMetCount()).isEqualTo(0);
        });
        assertThat(taskRepository.findById(conditionTaskId).orElseThrow().getFailCount()).isEqualTo(0);   // met never fails
    }

    private Pipeline createInstallAtConditionInProgress() {
        Pipeline pipeline = creator.create("obs-cond", PipelineType.INSTALL);
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
        pipelineWorker.pollOnce();   // dispatch plan terraform
        pipelineWorker.pollOnce();   // poll plan → DONE + promote apply READY
        pipelineWorker.pollOnce();   // dispatch apply terraform
        pipelineWorker.pollOnce();   // poll apply → DONE + promote condition READY
        pipelineWorker.pollOnce();   // dispatch condition → IN_PROGRESS
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(2).getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20; i++) {
            pipelineWorker.pollOnce();
            clock.advance(Duration.ofHours(1));
            if (pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus().isTerminal()) {
                return;
            }
        }
        throw new AssertionError("pipeline did not reach a terminal state in 20 polls");
    }

    private Long taskId(Pipeline pipeline, int sequence) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(sequence).getId();
    }
}
