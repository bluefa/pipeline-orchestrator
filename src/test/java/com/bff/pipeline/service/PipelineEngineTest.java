package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.client.TimeBoundedInfraManagerClient;
import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.model.TaskProgress;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 엔드-투-엔드 상태 머신 테스트이다. {@link PipelineWorker#pollOnce()}가 파이프라인을 태스크 체인 상에서
 * 한 번에 한 단계씩 구동한다(별도의 스케줄러 없이). fake에 동작을 스크립팅한 뒤 clock을 명시적으로
 * 진행시키고 {@code pollOnce()}를 실행한다. 각 {@code pollOnce()}는 tx1(claim) + tx2(report)를 독립적으로
 * 커밋하며({@code NOT_SUPPORTED}가 테스트 래핑 트랜잭션을 억제하여 프로덕션과 동일하게 동작한다).
 *
 * <p>명시적인 {@code BLOCKED} 상태는 첫 번째 이후의 모든 태스크에서 한 단계를 추가로 소요한다
 * ({@code BLOCKED → READY}는 퍼시스턴트 단계이다). 각 테스트의 실행 추적은 이 점을 반영하고 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TaskMachine.class, TaskTypeRegistry.class, TerraformTask.class,
        ConditionCheckTask.class, Observations.class, TaskCanceller.class, PipelineCreator.class,
        PipelineInserter.class, PipelineControl.class, Recipes.class,
        StepRunner.class, StepReporter.class, PipelineClaimer.class, PipelineWorker.class,
        TimeBoundedInfraManagerClient.class,
        PipelineEngineTest.Wiring.class, PipelineEngineTest.PostCheckWiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineEngineTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineWorker worker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManager;
    @Autowired private PostCheckProbeTask postCheckProbe;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManager.onDispatch(() -> "job-1");
        infraManager.onPoll(TerraformPoll::running);
        infraManager.onCheck(() -> false);
        postCheckProbe.onPostCheck(() -> TaskProgress.SUCCEEDED);
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

        // advance 1: dispatch seq0 → IN_PROGRESS
        // advance 2: poll seq0 → DONE; same tx2 promotes seq1 BLOCKED→READY (ADR Decision 4 §152)
        advance(pipeline);
        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.READY);

        // advance 3: dispatch seq1 → IN_PROGRESS
        // advance 4: poll seq1 → DONE; pipeline → DONE
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
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(2);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aThrowingDispatchIsTreatedAsCheckErrorAndRetriedThenFailed() {
        Pipeline pipeline = creator.create("t-throw", PipelineType.DELETE);
        infraManager.onDispatch(() -> {
            throw new InfraManagerClient.CallFailedException("InfraManager 503");
        });

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aRawRuntimeExceptionFromTheClientPropagatesAndIsNotRecordedAsCheckError() {
        Pipeline pipeline = creator.create("t-bug", PipelineType.DELETE);
        infraManager.onDispatch(() -> { throw new IllegalStateException("a real bug"); });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> worker.pollOnce())
            .isInstanceOf(RuntimeException.class);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getErrorCode()).isNull();
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(0);
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
        assertThat(attempts.findByTaskIdAndAttemptNumber(task(pipeline, 0).getId(), 1).orElseThrow().getErrorCode())
                .isEqualTo(ErrorCode.CALL_TIMEOUT);
    }

    @Test
    void aDispatchReturningNoJobIdIsTreatedAsCheckErrorAndRetried() {
        Pipeline pipeline = creator.create("t-null-job", PipelineType.DELETE);
        infraManager.onDispatch(() -> null);

        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getJobId()).isNull();
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attempts.findByTaskIdAndAttemptNumber(task(pipeline, 0).getId(), 1).orElseThrow().getErrorCode())
                .isEqualTo(ErrorCode.CHECK_ERROR);
    }

    @Test
    void aPollTimeoutIsCallTimeoutAndRecordsTheTimeoutObservation() {
        Pipeline pipeline = creator.create("t-poll-timeout", PipelineType.DELETE);
        advance(pipeline);   // dispatch → IN_PROGRESS, jobId job-1
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);

        infraManager.onPoll(() -> { throw new InfraManagerClient.CallTimeoutException(); });
        advance(pipeline);   // poll → CallTimeout → recordCheck(CALL_TIMEOUT) + retryOrFail(CALL_TIMEOUT)

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        TaskAttempt attempt = attempts.findByTaskIdAndAttemptNumber(task(pipeline, 0).getId(), 1).orElseThrow();
        assertThat(attempt.getErrorCode()).isEqualTo(ErrorCode.CALL_TIMEOUT);
        assertThat(checks.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallTimeoutCount()).isEqualTo(1);
            assertThat(check.getCallCount()).isEqualTo(1);
        });
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

    @Test
    void postCheckFailureFailsTheTask() {
        Pipeline pipeline = creator.create("t-pc-fail", PipelineType.DELETE);
        Task task0 = task(pipeline, 0);
        task0.setTaskName(PostCheckProbeTask.NAME);
        tasks.save(task0);
        postCheckProbe.onPostCheck(() -> TaskProgress.failedTerminal(ErrorCode.JOB_FAILED));

        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void postCheckCallFailureBecomesCheckErrorAndRetries() {
        Pipeline pipeline = creator.create("t-pc-callfail", PipelineType.DELETE);
        Task task0 = task(pipeline, 0);
        task0.setTaskName(PostCheckProbeTask.NAME);
        tasks.save(task0);
        postCheckProbe.onPostCheck(() -> { throw new InfraManagerClient.CallFailedException("post-check call failed"); });

        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attempts.findByTaskIdAndAttemptNumber(task(pipeline, 0).getId(), 1).orElseThrow().getErrorCode())
                .isEqualTo(ErrorCode.CHECK_ERROR);
    }

    @Test
    void postCheckPendingReschedulesAndKeepsRunning() {
        Pipeline pipeline = creator.create("t-pc-pending", PipelineType.DELETE);
        Task task0 = task(pipeline, 0);
        task0.setTaskName(PostCheckProbeTask.NAME);
        tasks.save(task0);
        postCheckProbe.onPostCheck(() -> TaskProgress.pending(CheckSignal.NOT_MET));

        advance(pipeline);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);

        advance(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline, 0).getNextCheckAt()).isAfter(clock.instant());
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.RUNNING);
    }


    private void advance(Pipeline pipeline) {
        worker.pollOnce();
    }

    private Pipeline createInstallAtConditionInProgress(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::success);
        // advance 1: dispatch seq0 (terraform) → IN_PROGRESS
        // advance 2: poll seq0 → DONE; same tx2 promotes seq1 BLOCKED→READY (ADR Decision 4 §152)
        // advance 3: dispatch seq1 (condition check) → IN_PROGRESS
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

        @Bean("infraManagerDelegate")
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .timeToLive(Duration.ofDays(7))
                    .pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2)
                    .build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2)
                    .leaseDuration(Duration.ofSeconds(30))
                    .apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(1000)
                    .slotCap(1000)
                    .slotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1))
                    .maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100))
                    .backoffMax(Duration.ofSeconds(1))
                    .jitterRatio(0.2)
                    .build();
        }

        @Bean(destroyMethod = "shutdown")
        ExecutorService imCallPool() {
            return Executors.newFixedThreadPool(2);
        }
    }

    @TestConfiguration
    static class PostCheckWiring {
        @Bean
        PostCheckProbeTask postCheckProbe() { return new PostCheckProbeTask(); }
    }

    /** 테스트용 {@link Clock} 구현체로, instant를 명시적으로 설정하거나 진행시킬 수 있어 테스트 추적에서 시간을 제어한다. */
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

    static final class PostCheckProbeTask implements com.bff.pipeline.model.TaskType {
        static final String NAME = "POST_CHECK_PROBE";
        private java.util.function.Supplier<TaskProgress> postCheckBehavior = () -> TaskProgress.SUCCEEDED;
        void onPostCheck(java.util.function.Supplier<TaskProgress> behavior) { this.postCheckBehavior = behavior; }
        public String taskName() { return NAME; }
        public void execute(String target, Task task) { }
        public TaskProgress check(String target, Task task) { return TaskProgress.SUCCEEDED; }
        public TaskProgress postCheck(String target, Task task) { return postCheckBehavior.get(); }
    }
}
