package com.bff.pipeline.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.control.PipelineControl;
import com.bff.pipeline.create.PipelineCreator;
import com.bff.pipeline.create.PipelineInserter;
import com.bff.pipeline.create.Recipes;
import com.bff.pipeline.domain.ErrorCode;
import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.domain.Task;
import com.bff.pipeline.domain.TaskStatus;
import com.bff.pipeline.im.FakeImClient;
import com.bff.pipeline.im.ImCall;
import com.bff.pipeline.im.ImClient;
import com.bff.pipeline.im.TerraformPoll;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * End-to-end state-machine tests: the reconciler drives a pipeline through its task chain.
 * Behavior is scripted on the fake, the clock is advanced explicitly, then {@code tick()} runs.
 *
 * <p>Note the explicit BLOCKED state costs one extra tick per non-first task (BLOCKED → READY is a
 * persisted step), which these traces account for.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({Reconciler.class, PipelineReconciliation.class, TaskMachine.class, Observations.class,
        PipelineCreator.class, PipelineInserter.class, PipelineControl.class, Recipes.class, ImCall.class,
        ReconcilerTest.Wiring.class})
class ReconcilerTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private Reconciler reconciler;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private MutableClock clock;
    @Autowired private FakeImClient im;

    @BeforeEach
    void reset() {
        clock.set(START);
        im.onDispatch(() -> "job-1");
        im.onPoll(TerraformPoll::running);
        im.onCheck(() -> false);
    }

    @Test
    void terraformJobHappyPathReachesDoneAndCompletesTheDeletePipeline() {
        Pipeline pipeline = creator.create("t-happy", PipelineType.DELETE);
        im.onPoll(TerraformPoll::success);

        reconciler.tick(); // READY → dispatch → IN_PROGRESS
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline, 0).getJobId()).isEqualTo("job-1");

        reconciler.tick(); // IN_PROGRESS → poll succeeds → DONE → pipeline DONE
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void anInstallRunsBothTasksThroughBlockedAndUnblockToDone() {
        Pipeline pipeline = creator.create("t-install", PipelineType.INSTALL);
        im.onPoll(TerraformPoll::success);
        im.onCheck(() -> true);

        // seq1 (condition) starts BLOCKED behind the terraform task.
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.BLOCKED);

        reconciler.tick(); // seq0 dispatch → IN_PROGRESS
        reconciler.tick(); // seq0 poll → DONE
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.BLOCKED);

        reconciler.tick(); // seq1 BLOCKED → READY (unblock)
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.READY);

        reconciler.tick(); // seq1 (condition) dispatch → IN_PROGRESS
        reconciler.tick(); // seq1 poll: met → DONE → pipeline DONE
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void terraformJobFailureRetriesThenFailsAtMaxAndFailsThePipeline() {
        Pipeline pipeline = creator.create("t-fail", PipelineType.DELETE);
        im.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(3);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aThrowingDispatchIsTreatedAsCheckErrorAndRetriedThenFailed() {
        Pipeline pipeline = creator.create("t-throw", PipelineType.DELETE);
        im.onDispatch(() -> {
            throw new RuntimeException("InfraManager 503");
        });

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aSlowDispatchTimesOutAsCallTimeoutAndIncrementsFailCount() {
        Pipeline pipeline = creator.create("t-slow", PipelineType.DELETE);
        im.onDispatch(() -> {
            FakeImClient.sleepPastTimeout();
            return "job-late";
        });

        reconciler.tick(); // dispatch exceeds the per-call timeout → CALL_TIMEOUT → retry

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
    }

    @Test
    void terraformJobRunningPastExecutionTimeoutFailsWithExecutionTimeout() {
        Pipeline pipeline = creator.create("t-exec", PipelineType.DELETE);
        im.onPoll(TerraformPoll::running); // never finishes; each retry runs past the timeout

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
    }

    @Test
    void conditionNotMetReschedulesByThePollingIntervalAndDoesNotFail() {
        Pipeline pipeline = createInstallAtConditionInProgress("t-wait");

        reconciler.tick(); // condition not met → reschedule

        Task condition = task(pipeline, 1);
        assertThat(condition.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(condition.getNextCheckAt()).isAfter(clock.instant());
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.RUNNING);
    }

    @Test
    void conditionPastTtlExpiresToFailedWithTtlExpired() {
        Pipeline pipeline = createInstallAtConditionInProgress("t-ttl");

        clock.advance(Duration.ofDays(8)); // past the 7-day TTL
        reconciler.tick();

        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 1).getErrorCode()).isEqualTo(ErrorCode.TTL_EXPIRED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aFailedPipelineCancelsItsRemainingBlockedTasks() {
        Pipeline pipeline = creator.create("t-cascade", PipelineType.INSTALL);
        im.onPoll(TerraformPoll::failure); // the terraform task fails

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void cancelMidFlightCancelsThePipelineAndTheTickSkipsIt() {
        Pipeline pipeline = creator.create("t-cancel", PipelineType.DELETE);
        reconciler.tick(); // → IN_PROGRESS
        im.onPoll(TerraformPoll::success);

        control.cancel(pipeline.getId());
        reconciler.tick(); // pipeline no longer RUNNING → skipped, success is dropped

        assertThat(status(pipeline)).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void crashResumeRePollsAnInProgressTerraformJobByItsStoredJobId() {
        Pipeline pipeline = creator.create("t-crash", PipelineType.DELETE);
        reconciler.tick(); // dispatch → IN_PROGRESS, job-1 stored
        assertThat(task(pipeline, 0).getJobId()).isEqualTo("job-1");

        // "Restart": the row is the only state. The job finished while we were down.
        im.onPoll(TerraformPoll::success);
        reconciler.tick();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    // --- helpers ---

    /** Drive an INSTALL pipeline up to its condition task sitting IN_PROGRESS. */
    private Pipeline createInstallAtConditionInProgress(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.INSTALL);
        im.onPoll(TerraformPoll::success);
        reconciler.tick(); // seq0 dispatch
        reconciler.tick(); // seq0 DONE
        reconciler.tick(); // seq1 unblock → READY
        reconciler.tick(); // seq1 dispatch → IN_PROGRESS
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        return pipeline;
    }

    /** Tick to a terminal pipeline, advancing the clock each step so retries and deadlines progress. */
    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20 && status(pipeline) == PipelineStatus.RUNNING; i++) {
            reconciler.tick();
            clock.advance(Duration.ofHours(1)); // crosses the 30m execution timeout on each retry
        }
    }

    private Task task(Pipeline pipeline, int seq) {
        List<Task> chain = tasks.findByPipelineIdOrderBySeqAsc(pipeline.getId());
        return chain.get(seq);
    }

    private PipelineStatus status(Pipeline pipeline) {
        return pipelines.findById(pipeline.getId()).orElseThrow().getStatus();
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeImClient im() {
            return new FakeImClient();
        }

        @Bean
        ImClient imClient(FakeImClient fake) {
            return fake;
        }

        @Bean
        PipelineSettings pipelineSettings() {
            // Short per-call timeout so the CALL_TIMEOUT test is fast; other knobs as default.
            return new PipelineSettings(
                    Duration.ofSeconds(15), Duration.ofMillis(300), Duration.ofMinutes(30),
                    Duration.ofDays(7), Duration.ofMinutes(10), 3, 4);
        }

        @Bean(destroyMethod = "shutdown")
        ExecutorService imCallPool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant t) {
            this.now = t;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
