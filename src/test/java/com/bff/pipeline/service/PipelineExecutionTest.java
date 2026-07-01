package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
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
import com.bff.pipeline.service.task.terraform.TerraformTask;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.bff.pipeline.exception.CallFailedException;

/**
 * ADR-021 claim-pull 실행 모델의 엔드-투-엔드 테스트이다. {@link PipelineWorker#pollOnce}가 due pipeline 하나를
 * claim(claim 트랜잭션) → 외부호출(run 단계) → write-back(write-back 트랜잭션)으로 한 단계씩 구동한다(스케줄러 스레드 없이 결정적으로). fake에 동작을
 * 스크립팅하고 {@link MutableClock}으로 due-ness/lease 만료를 제어한다. {@code NOT_SUPPORTED}가 테스트 래핑
 * 트랜잭션을 억제하므로 각 claim 트랜잭션과 write-back 트랜잭션이 프로덕션과 동일하게 독립 커밋한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, ConditionCheckTask.class,
        ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class, PipelineInserter.class,
        PipelineControl.class, RecipeCatalog.class, PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineExecutionTest {

    static final Instant START = Instant.parse("2026-06-23T00:00:00Z");
    static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineClaimer pipelineClaimer;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
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
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    // ── e2e domain through the two-transaction pipelineWorker ────────────────────────────────────────────

    @Test
    void terraformHappyPathReachesDoneAndRecordsTheResponse() {
        Pipeline pipeline = creator.create("e-happy", PipelineType.DELETE);
        infraManagerClient.onPoll(TerraformPoll::success);

        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(attempt(pipeline, 0).getResponse()).isEqualTo("[\"job-1\"]");

        pipelineWorker.pollOnce();   // poll → DONE
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void installPromotesTheSuccessorInTheSameReportAndFinishes() {
        Pipeline pipeline = creator.create("e-install", PipelineType.INSTALL);
        infraManagerClient.onPoll(TerraformPoll::success);
        infraManagerClient.onCheck(() -> true);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.BLOCKED);

        pipelineWorker.pollOnce();   // dispatch terraform
        pipelineWorker.pollOnce();   // poll terraform success → DONE + promote condition BLOCKED→READY (same write-back 트랜잭션)
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.READY);

        pipelineWorker.pollOnce();   // dispatch condition (NONE) → IN_PROGRESS
        pipelineWorker.pollOnce();   // poll condition met → DONE → pipeline DONE
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void conditionNotMetRetriesThenFailsAtMaxWithConditionNotMet() {
        Pipeline pipeline = creator.create("e-cond-fail", PipelineType.INSTALL);
        infraManagerClient.onPoll(TerraformPoll::success);
        infraManagerClient.onCheck(() -> false);   // condition never met → each poll is a failed poll

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);   // terraform succeeded first
        Task condition = task(pipeline, 1);
        assertThat(condition.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(condition.getErrorCode()).isEqualTo(ErrorCode.CONDITION_NOT_MET);
        assertThat(condition.getFailCount()).isEqualTo(2);   // bounded by maxFailCount, not a wall-clock ttl
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void terraformFailureRetriesThenFailsAtMaxAndCascadeCancels() {
        Pipeline pipeline = creator.create("e-fail", PipelineType.INSTALL);
        infraManagerClient.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(2);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aLostDispatchResponseRidesExecutionTimeoutThenSharesTheFailCount() {
        Pipeline pipeline = creator.create("e-lost", PipelineType.DELETE);
        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS, response recorded

        TaskAttempt attempt = attempt(pipeline, 0);
        attempt.setResponse(null);
        taskAttemptRepository.saveAndFlush(attempt);

        clock.advance(Duration.ofHours(1));
        pipelineWorker.pollOnce();   // response lost → executionTimeout fallthrough → retryable EXECUTION_TIMEOUT

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
    }

    @Test
    void aThrowingDispatchIsCheckErrorAndRetries() {
        Pipeline pipeline = creator.create("e-throw", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> { throw new CallFailedException("503"); });

        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
    }

    @Test
    void aRawRuntimeExceptionFromTheClientPropagatesOutOfProcess() {
        Pipeline pipeline = creator.create("e-bug", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> { throw new IllegalStateException("a real bug"); });
        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();

        assertThatThrownBy(() -> pipelineWorker.process(claim)).isInstanceOf(RuntimeException.class);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(0);
    }

    @Test
    void aMalformedDispatchResponseFailsTheTaskOutright() {
        Pipeline pipeline = creator.create("e-malformed", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "not-json");

        pipelineWorker.pollOnce();   // dispatch records "not-json"
        pipelineWorker.pollOnce();   // check cannot deserialize → CHECK_ERROR outright

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(0);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[null]", "[\"\"]"})
    void aResponseWithNoUsableJobIdsFailsTheTaskOutright(String response) {
        Pipeline pipeline = creator.create("e-no-jobs", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> response);

        pipelineWorker.pollOnce();
        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
    }

    @Test
    void nJobsCompleteOnlyWhenAllSucceed() {
        Pipeline pipeline = creator.create("e-n", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\",\"job-3\"]");
        infraManagerClient.onPoll(TerraformPoll::success);

        pipelineWorker.pollOnce();
        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void anUnknownTaskNameFailsAsUnknownTask() {
        Pipeline pipeline = creator.create("e-unknown", PipelineType.DELETE);
        Task task = task(pipeline, 0);
        task.setTaskName("NO_SUCH_TYPE");
        taskRepository.save(task);

        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_TASK);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    // ── claim / lease / fencing (Decision 2, 4, 5) ───────────────────────────────────────────────

    @Test
    void creationSeedsNextDueAtSoThePipelineIsImmediatelyClaimable() {
        Pipeline pipeline = creator.create("c-seed", PipelineType.DELETE);
        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getNextDueAt()).isEqualTo(START);
        assertThat(pipelineClaimer.claimOneDue()).isPresent();
    }

    @Test
    void aClaimStampsAFreshTokenAndLeaseAndBlocksASecondClaim() {
        creator.create("c-claim", PipelineType.DELETE);

        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();

        Pipeline claimed = pipelineRepository.findById(claim.pipelineId()).orElseThrow();
        assertThat(claimed.getClaimedBy()).isEqualTo(claim.token());
        assertThat(claimed.getClaimedUntil()).isEqualTo(START.plus(LEASE));
        assertThat(pipelineClaimer.claimOneDue()).isEmpty();   // live lease → not claimable
    }

    @Test
    void anExpiredLeaseIsReclaimedWithADifferentToken() {
        creator.create("c-reclaim", PipelineType.DELETE);
        String first = pipelineClaimer.claimOneDue().orElseThrow().token();

        clock.advance(LEASE.plusSeconds(1));
        String second = pipelineClaimer.claimOneDue().orElseThrow().token();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void aSuccessfulDispatchReleasesTheClaimAndAdvancesNextDueAt() {
        Pipeline pipeline = creator.create("c-release", PipelineType.DELETE);

        pipelineWorker.pollOnce();   // dispatch + report

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(after.getClaimedBy()).isNull();
        assertThat(after.getClaimedUntil()).isNull();
        assertThat(after.getNextDueAt()).isNotNull();
    }

    @Test
    void aStaleTokenReportNoOpsAfterReclaim() {
        Pipeline pipeline = creator.create("c-stale", PipelineType.DELETE);
        String stale = pipelineClaimer.claimOneDue().orElseThrow().token();
        clock.advance(LEASE.plusSeconds(1));
        String fresh = pipelineClaimer.claimOneDue().orElseThrow().token();
        assertThat(fresh).isNotEqualTo(stale);

        pipelineWorker.process(new Claim(pipeline.getId(), stale));   // old token

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);   // untouched
        assertThat(after.getClaimedBy()).isEqualTo(fresh);                       // fresh claim intact
    }

    @Test
    void aMatchingTokenReportStillAppliesAfterLeaseExpiryWhenNobodyReclaimed() {
        Pipeline pipeline = creator.create("c-token-only", PipelineType.DELETE);
        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();
        clock.advance(LEASE.plusSeconds(1));   // lease expired, but token unchanged and nobody reclaimed

        pipelineWorker.process(claim);   // token-only guard → applies

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    // ── cancel (Decision 6) ──────────────────────────────────────────────────────────────────────

    @Test
    void cancelCaseAIdleTerminatesImmediatelyAndClearsTheClaim() {
        Pipeline pipeline = creator.create("x-idle", PipelineType.INSTALL);

        Pipeline cancelled = control.cancel(pipeline.getId());

        assertThat(cancelled.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(cancelled.getActiveTarget()).isNull();
        assertThat(cancelled.getClaimedBy()).isNull();
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()))
                .extracting(Task::getStatus).containsOnly(TaskStatus.CANCELLED);
    }

    @Test
    void cancelCaseAExpiredLeaseStillTerminatesImmediately() {
        Pipeline pipeline = creator.create("x-expired", PipelineType.DELETE);
        pipelineClaimer.claimOneDue();
        clock.advance(LEASE.plusSeconds(1));

        control.cancel(pipeline.getId());

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(after.getClaimedBy()).isNull();
    }

    @Test
    void cancelCaseBLiveLeaseOnlyRaisesTheFlagThenTheClaimHolderTerminalizes() {
        Pipeline pipeline = creator.create("x-live", PipelineType.DELETE);
        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();

        control.cancel(pipeline.getId());

        Pipeline flagged = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(flagged.getStatus()).isEqualTo(PipelineStatus.RUNNING);   // API did not write status
        assertThat(flagged.isCancelRequested()).isTrue();
        assertThat(flagged.getNextDueAt()).isEqualTo(START);

        pipelineWorker.process(claim);   // claim holder observes the flag at its safe point

        Pipeline done = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void aStaleStragglerCannotResurrectAfterCaseACancel() {
        Pipeline pipeline = creator.create("x-resurrect", PipelineType.DELETE);
        Claim straggler = pipelineClaimer.claimOneDue().orElseThrow();
        clock.advance(LEASE.plusSeconds(1));
        control.cancel(pipeline.getId());   // Case A clears the token

        pipelineWorker.process(straggler);   // old token → no-op

        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20 && status(pipeline) == PipelineStatus.RUNNING; i++) {
            pipelineWorker.pollOnce();
            clock.advance(Duration.ofHours(1));
        }
        assertThat(status(pipeline).isTerminal())
                .as("pipeline did not reach a terminal state in 20 polls")
                .isTrue();
    }

    private Task task(Pipeline pipeline, int sequence) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(sequence);
    }

    private TaskAttempt attempt(Pipeline pipeline, int sequence) {
        return attemptNo(pipeline, sequence, task(pipeline, sequence).getFailCount() + 1);
    }

    private TaskAttempt attemptNo(Pipeline pipeline, int sequence, int attemptNumber) {
        return taskAttemptRepository.findByTaskIdAndAttemptNumber(task(pipeline, sequence).getId(), attemptNumber).orElseThrow();
    }

    private PipelineStatus status(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus();
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeInfraManagerClient infraManagerClient() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2)
                    .build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2).leaseDuration(LEASE).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }
    }
}
