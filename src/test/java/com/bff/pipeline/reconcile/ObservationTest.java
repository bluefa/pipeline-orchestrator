package com.bff.pipeline.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.create.PipelineCreator;
import com.bff.pipeline.create.PipelineInserter;
import com.bff.pipeline.create.Recipes;
import com.bff.pipeline.domain.ErrorCode;
import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.domain.TaskAttempt;
import com.bff.pipeline.domain.TaskStatus;
import com.bff.pipeline.im.FakeImClient;
import com.bff.pipeline.im.ImCall;
import com.bff.pipeline.im.TerraformPoll;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * The write-only observation tables: one {@code task_attempt} per attempt with the right
 * {@code attempt_no}, and one {@code task_check} per attempt updated in place (not one row per poll).
 * Reuses {@link ReconcilerTest}'s wiring (fake InfraManager, mutable clock).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({Reconciler.class, PipelineReconciliation.class, TaskMachine.class, Observations.class,
        PipelineCreator.class, PipelineInserter.class, Recipes.class, ImCall.class, ReconcilerTest.Wiring.class})
class ObservationTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private Reconciler reconciler;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private ReconcilerTest.MutableClock clock;
    @Autowired private FakeImClient im;

    @BeforeEach
    void reset() {
        clock.set(START);
        im.onDispatch(() -> "job-1");
        im.onPoll(TerraformPoll::running);
        im.onCheck(() -> false);
    }

    @Test
    void aHappyTerraformTaskRecordsOneDoneAttemptWithItsJobId() {
        Pipeline pipeline = creator.create("obs-happy", PipelineType.DELETE);
        im.onPoll(TerraformPoll::success);
        reconciler.tick(); // dispatch → begins attempt 1, records job id
        reconciler.tick(); // poll succeeds → ends attempt 1 as DONE

        List<TaskAttempt> recorded = attempts.findByTaskIdOrderByAttemptNoAsc(taskId(pipeline, 0));

        assertThat(recorded).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNo()).isEqualTo(1);
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(attempt.getJobId()).isEqualTo("job-1");
        });
        // No intermediate poll churned, so no check row exists.
        assertThat(checks.findByTaskAttemptId(recorded.getFirst().getId())).isEmpty();
    }

    @Test
    void aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo() {
        Pipeline pipeline = creator.create("obs-retry", PipelineType.DELETE);
        im.onPoll(TerraformPoll::failure); // fails every time → retries to the cap of 3

        runUntilTerminal(pipeline);

        List<TaskAttempt> recorded = attempts.findByTaskIdOrderByAttemptNoAsc(taskId(pipeline, 0));
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

        for (int i = 0; i < 3; i++) {        // three not-met polls, spaced past the polling interval
            reconciler.tick();
            clock.advance(Duration.ofMinutes(11));
        }

        TaskAttempt attempt = attempts.findByTaskIdAndAttemptNo(conditionTaskId, 1).orElseThrow();
        assertThat(checks.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(3);     // one row, counters incremented...
            assertThat(check.getNotMetCount()).isEqualTo(3);   // ...not three rows
        });
        assertThat(checks.findAll()).hasSize(1);
    }

    private Pipeline createInstallAtConditionInProgress() {
        Pipeline pipeline = creator.create("obs-cond", PipelineType.INSTALL);
        im.onPoll(TerraformPoll::success);
        reconciler.tick(); // seq0 dispatch
        reconciler.tick(); // seq0 DONE
        reconciler.tick(); // seq1 unblock → READY
        reconciler.tick(); // seq1 dispatch → IN_PROGRESS
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20; i++) {
            reconciler.tick();
            clock.advance(Duration.ofHours(1));
        }
    }

    private Long taskId(Pipeline pipeline, int seq) {
        return tasks.findByPipelineIdOrderBySeqAsc(pipeline.getId()).get(seq).getId();
    }
}
