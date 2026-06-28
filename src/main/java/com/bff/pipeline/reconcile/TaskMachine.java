package com.bff.pipeline.reconcile;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.domain.ErrorCode;
import com.bff.pipeline.domain.Task;
import com.bff.pipeline.domain.TaskKind;
import com.bff.pipeline.domain.TaskStatus;
import com.bff.pipeline.im.ImCall;
import com.bff.pipeline.im.ImClient;
import com.bff.pipeline.im.TerraformPoll;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Advances ONE task one step (ADR-016 §2, §6). The current task is handed in by
 * {@link PipelineReconciliation}; this class owns only the per-task transition.
 *
 * <pre>
 *   BLOCKED      → unblock → READY                    (predecessor reached DONE)
 *   READY        → dispatch (synchronous, idempotent) → IN_PROGRESS
 *   IN_PROGRESS  → poll:
 *                    TERRAFORM_JOB  : succeeded → DONE; failed → retry-or-fail(JOB_FAILED);
 *                                     past executionTimeout → retry-or-fail(EXECUTION_TIMEOUT)
 *                    CONDITION_CHECK: met → DONE; not met → reschedule;
 *                                     past ttl → FAILED(TTL_EXPIRED)
 * </pre>
 *
 * <b>Exception strategy:</b> every external call goes through {@link ImCall}; a timeout or any other
 * thrown failure is caught here and turned into a retry or a persisted {@link ErrorCode} — no
 * external exception escapes as an exception. Business outcomes (job failed, ttl expired) are never
 * exceptions; they are {@code ErrorCode} values written to the row. See
 * {@code docs/exception-strategy.md}.
 */
@Component
public class TaskMachine {

    private final ImClient im;
    private final ImCall imCall;
    private final TaskRepository tasks;
    private final Observations observations;
    private final PipelineSettings settings;
    private final Clock clock;

    public TaskMachine(ImClient im, ImCall imCall, TaskRepository tasks, Observations observations,
            PipelineSettings settings, Clock clock) {
        this.im = im;
        this.imCall = imCall;
        this.tasks = tasks;
        this.observations = observations;
        this.settings = settings;
        this.clock = clock;
    }

    void advance(String target, Task task) {
        switch (task.getStatus()) {
            case BLOCKED -> unblock(task);
            case READY -> dispatch(target, task);
            case IN_PROGRESS -> poll(target, task);
            case DONE, FAILED, CANCELLED -> { /* terminal — not serviced */ }
        }
    }

    /** The predecessor reached DONE, so this task is now current; make it dispatchable next tick. */
    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        tasks.save(task);
    }

    private void dispatch(String target, Task task) {
        observations.beginAttempt(task);
        if (task.getKind() == TaskKind.TERRAFORM_JOB) {
            String jobId;
            try {
                jobId = imCall.withTimeout(() -> im.runTerraform(target, task.getOperation()));
            } catch (ImCall.CallInterruptedException e) {
                throw e; // fail-fast: an interrupt aborts the tick, it is not a business failure
            } catch (ImCall.CallTimeoutException e) {
                retryOrFail(task, ErrorCode.CALL_TIMEOUT);
                return;
            } catch (RuntimeException e) {
                retryOrFail(task, ErrorCode.CHECK_ERROR);
                return;
            }
            // Idempotent (ADR-016 §5): a crash before this save is healed by re-dispatch next tick.
            task.setJobId(jobId);
            observations.recordJobId(task, jobId);
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(clock.instant());
        task.setNextCheckAt(clock.instant());
        tasks.save(task);
    }

    private void poll(String target, Task task) {
        if (task.getKind() == TaskKind.TERRAFORM_JOB) {
            pollTerraform(task);
        } else {
            pollCondition(target, task);
        }
    }

    private void pollTerraform(Task task) {
        TerraformPoll result;
        try {
            result = imCall.withTimeout(() -> im.terraformJobStatus(task.getJobId()));
        } catch (ImCall.CallInterruptedException e) {
            throw e; // fail-fast: an interrupt aborts the tick, it is not a business failure
        } catch (ImCall.CallTimeoutException e) {
            observations.recordCheck(task, Observations.CheckSignal.CALL_TIMEOUT);
            retryOrFail(task, ErrorCode.CALL_TIMEOUT);
            return;
        } catch (RuntimeException e) {
            observations.recordCheck(task, Observations.CheckSignal.API_ERROR);
            retryOrFail(task, ErrorCode.CHECK_ERROR);
            return;
        }
        if (result.finished()) {
            if (result.succeeded()) {
                complete(task);
            } else {
                retryOrFail(task, ErrorCode.JOB_FAILED);
            }
            return;
        }
        observations.recordCheck(task, Observations.CheckSignal.RUNNING);
        if (TaskKnobs.pastDeadline(task, TaskKnobs.executionTimeout(task, settings), clock)) {
            retryOrFail(task, ErrorCode.EXECUTION_TIMEOUT);
            return;
        }
        reschedule(task, settings.tickInterval());
    }

    private void pollCondition(String target, Task task) {
        boolean met;
        try {
            met = imCall.withTimeout(() -> im.checkCondition(target, task.getOperation()));
        } catch (ImCall.CallInterruptedException e) {
            throw e; // fail-fast: an interrupt aborts the tick, it is not a business failure
        } catch (ImCall.CallTimeoutException e) {
            observations.recordCheck(task, Observations.CheckSignal.CALL_TIMEOUT);
            retryOrFail(task, ErrorCode.CALL_TIMEOUT);
            return;
        } catch (RuntimeException e) {
            observations.recordCheck(task, Observations.CheckSignal.API_ERROR);
            retryOrFail(task, ErrorCode.CHECK_ERROR);
            return;
        }
        if (met) {
            complete(task);
            return;
        }
        observations.recordCheck(task, Observations.CheckSignal.NOT_MET);
        if (TaskKnobs.pastDeadline(task, TaskKnobs.ttl(task, settings), clock)) {
            observations.endAttempt(task, TaskStatus.FAILED, ErrorCode.TTL_EXPIRED);
            fail(task, ErrorCode.TTL_EXPIRED); // an expired condition does not retry
            return;
        }
        reschedule(task, TaskKnobs.pollingInterval(task, settings));
    }

    /** A failed dispatch/poll: end this attempt, count it, then re-run fresh (READY) if under the cap, else FAIL. */
    private void retryOrFail(Task task, ErrorCode reason) {
        // End the attempt BEFORE incrementing failCount, so it lands on the right attempt_no.
        observations.endAttempt(task, TaskStatus.FAILED, reason);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskKnobs.maxFailCount(task, settings)) {
            fail(task, reason);
            return;
        }
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        task.setJobId(null);
        task.setNextCheckAt(null); // due immediately for the fresh re-dispatch
        tasks.save(task);
    }

    private void complete(Task task) {
        task.setStatus(TaskStatus.DONE);
        task.setFinishedAt(clock.instant());
        tasks.save(task);
        observations.endAttempt(task, TaskStatus.DONE, null);
    }

    private void fail(Task task, ErrorCode reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setErrorCode(reason);
        task.setFinishedAt(clock.instant());
        tasks.save(task);
    }

    private void reschedule(Task task, Duration after) {
        task.setNextCheckAt(clock.instant().plus(after));
        tasks.save(task);
    }
}
