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
 * The write-only observation tables: one {@code task_attempt} per attempt with the right
 * {@code attempt_no}, and one {@code task_check} per attempt updated in place (not one row per poll).
 * Reuses {@link PipelineEngineTest}'s wiring (fake InfraManager, mutable clock). {@code NOT_SUPPORTED}
 * suppresses the test-wrapping transaction so the observation rows survive each step's commit.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineEngine.class, TaskMachine.class, TaskTypeRegistry.class, TerraformTask.class,
        ConditionCheckTask.class, Observations.class, PipelineCreator.class, PipelineInserter.class,
        Recipes.class, PipelineEngineTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ObservationTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineEngine engine;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private PipelineEngineTest.MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManager;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManager.onDispatch(() -> "job-1");
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
    void aHappyTerraformTaskRecordsOneDoneAttemptWithItsJobId() {
        Pipeline pipeline = creator.create("obs-happy", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::success);
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());

        var recorded = attempts.findByTaskIdOrderByAttemptNoAsc(taskId(pipeline, 0));

        assertThat(recorded).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNo()).isEqualTo(1);
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(attempt.getJobId()).isEqualTo("job-1");
        });
        assertThat(checks.findByTaskAttemptId(recorded.getFirst().getId())).isEmpty();
    }

    @Test
    void aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo() {
        Pipeline pipeline = creator.create("obs-retry", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        var recorded = attempts.findByTaskIdOrderByAttemptNoAsc(taskId(pipeline, 0));
        assertThat(recorded).extracting(TaskAttempt::getAttemptNo).containsExactly(1, 2, 3);
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
            engine.advance(pipeline.getId());
            clock.advance(Duration.ofMinutes(11));
        }

        TaskAttempt attempt = attempts.findByTaskIdAndAttemptNo(conditionTaskId, 1).orElseThrow();
        assertThat(checks.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(3);
            assertThat(check.getNotMetCount()).isEqualTo(3);
        });
        assertThat(checks.findAll()).hasSize(1);
    }

    private Pipeline createInstallAtConditionInProgress() {
        Pipeline pipeline = creator.create("obs-cond", PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::success);
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20; i++) {
            engine.advance(pipeline.getId());
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
