package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.PipelineSettings;
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
import com.bff.pipeline.service.terraform.TerraformTask;
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
        Recipes.class, PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ObservationTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineWorker worker;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManager;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManager.onDispatch(() -> "[\"job-1\"]");
        infraManager.onPoll(TerraformPoll::running);
        infraManager.onCheck(() -> false);
    }

    @AfterEach
    void clean() {
        checks.deleteAll();
        attempts.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void aHappyTerraformTaskRecordsOneDoneAttemptWithItsResponse() {
        Pipeline pipeline = creator.create("obs-happy", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::success);
        worker.pollOnce();
        worker.pollOnce();

        var recorded = attempts.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));
        assertThat(recorded).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNumber()).isEqualTo(1);
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(attempt.getResponse()).isEqualTo("[\"job-1\"]");
        });
        assertThat(checks.findByTaskAttemptId(recorded.getFirst().getId())).isEmpty();
    }

    @Test
    void aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo() {
        Pipeline pipeline = creator.create("obs-retry", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        var recorded = attempts.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));
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
            worker.pollOnce();
            clock.advance(Duration.ofMinutes(11));
        }

        TaskAttempt attempt = attempts.findByTaskIdAndAttemptNumber(conditionTaskId, 1).orElseThrow();
        assertThat(checks.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(3);
            assertThat(check.getNotMetCount()).isEqualTo(3);
        });
        assertThat(checks.findAll()).hasSize(1);
    }

    private Pipeline createInstallAtConditionInProgress() {
        Pipeline pipeline = creator.create("obs-cond", PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::success);
        worker.pollOnce();   // dispatch terraform
        worker.pollOnce();   // poll terraform → DONE + promote condition READY
        worker.pollOnce();   // dispatch condition → IN_PROGRESS
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(1).getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20; i++) {
            worker.pollOnce();
            clock.advance(Duration.ofHours(1));
            if (pipelines.findById(pipeline.getId()).orElseThrow().getStatus() != PipelineStatus.RUNNING) {
                return;
            }
        }
    }

    private Long taskId(Pipeline pipeline, int sequence) {
        return tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(sequence).getId();
    }
}
