package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * End-to-end state-machine tests: {@link PipelineEngine#advance} drives a pipeline through its task
 * chain one step at a time (no runner/scheduler — that is ADR-021). Behavior is scripted on the fake,
 * the clock is advanced explicitly, then {@code advance()} runs. Each {@code advance()} commits
 * independently (NOT_SUPPORTED suppresses the test-wrapping transaction), like production.
 *
 * <p>The explicit BLOCKED state costs one extra step per non-first task (BLOCKED → READY is a
 * persisted step), which these traces account for.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineEngine.class, TaskMachine.class, TaskTypeRegistry.class, TerraformTask.class,
        ConditionCheckTask.class, Observations.class, PipelineCreator.class, PipelineInserter.class,
        PipelineControl.class, Recipes.class, PipelineEngineTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineEngineTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineEngine engine;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private MutableClock clock;
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
    void terraformJobHappyPathReachesDoneAndCompletesTheDeletePipeline() {
        Pipeline pipeline = creator.create("t-happy", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::success);

        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline, 0).getJobId()).isEqualTo("job-1");

        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void anInstallRunsBothTasksThroughBlockedAndUnblockToDone() {
        Pipeline pipeline = creator.create("t-install", PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::success);
        infraManager.onCheck(() -> true);

        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.BLOCKED);

        advance(pipeline);
        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.BLOCKED);

        advance(pipeline);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.READY);

        advance(pipeline);
        advance(pipeline);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void terraformJobFailureRetriesThenFailsAtMaxAndFailsThePipeline() {
        Pipeline pipeline = creator.create("t-fail", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(3);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aThrowingDispatchIsTreatedAsCheckErrorAndRetriedThenFailed() {
        Pipeline pipeline = creator.create("t-throw", PipelineType.DELETE);
        infraManager.onDispatch(() -> {
            throw new RuntimeException("InfraManager 503");
        });

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aCallTimeoutFromTheInfraManagerClientIsCallTimeoutAndRetries() {
        Pipeline pipeline = creator.create("t-timeout", PipelineType.DELETE);
        infraManager.onDispatch(() -> {
            throw new InfraManagerClient.CallTimeoutException();
        });

        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
    }

    @Test
    void aDispatchReturningNoJobIdIsTreatedAsCheckErrorAndRetried() {
        Pipeline pipeline = creator.create("t-null-job", PipelineType.DELETE);
        infraManager.onDispatch(() -> null);

        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getJobId()).isNull();
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
    }

    @Test
    void terraformJobRunningPastExecutionTimeoutFailsWithExecutionTimeout() {
        Pipeline pipeline = creator.create("t-exec", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::running);

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
    }

    @Test
    void conditionNotMetReschedulesByThePollingIntervalAndDoesNotFail() {
        Pipeline pipeline = createInstallAtConditionInProgress("t-wait");

        advance(pipeline);

        Task condition = task(pipeline, 1);
        assertThat(condition.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(condition.getNextCheckAt()).isAfter(clock.instant());
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.RUNNING);
    }

    @Test
    void conditionPastTimeToLiveExpiresToFailedWithTimeToLiveExpired() {
        Pipeline pipeline = createInstallAtConditionInProgress("t-time-to-live");

        clock.advance(Duration.ofDays(8));
        advance(pipeline);

        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 1).getErrorCode()).isEqualTo(ErrorCode.TIME_TO_LIVE_EXPIRED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aFailedPipelineCancelsItsRemainingBlockedTasks() {
        Pipeline pipeline = creator.create("t-cascade", PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void cancelMidFlightCancelsThePipelineAndTheNextAdvanceSkipsIt() {
        Pipeline pipeline = creator.create("t-cancel", PipelineType.DELETE);
        advance(pipeline);
        infraManager.onPoll(TerraformPoll::success);

        control.cancel(pipeline.getId());
        advance(pipeline);

        assertThat(status(pipeline)).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void crashResumeRePollsAnInProgressTerraformJobByItsStoredJobId() {
        Pipeline pipeline = creator.create("t-crash", PipelineType.DELETE);
        advance(pipeline);
        assertThat(task(pipeline, 0).getJobId()).isEqualTo("job-1");

        infraManager.onPoll(TerraformPoll::success);
        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void aTaskWhoseStoredNameHasNoRegisteredTypeFailsAsUnknownTask() {
        Pipeline pipeline = creator.create("t-unknown", PipelineType.DELETE);
        Task task = task(pipeline, 0);
        task.setTaskName("NO_SUCH_TYPE");
        tasks.save(task);

        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_TASK);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }


    private void advance(Pipeline pipeline) {
        engine.advance(pipeline.getId());
    }

    private Pipeline createInstallAtConditionInProgress(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::success);
        advance(pipeline);
        advance(pipeline);
        advance(pipeline);
        advance(pipeline);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20 && status(pipeline) == PipelineStatus.RUNNING; i++) {
            advance(pipeline);
            clock.advance(Duration.ofHours(1));
        }
    }

    private Task task(Pipeline pipeline, int sequence) {
        List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
        return chain.get(sequence);
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
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return new PipelineSettings(Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(10), 3);
        }
    }

    /** A test {@link Clock} whose instant is set and advanced explicitly so traces control time. */
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
