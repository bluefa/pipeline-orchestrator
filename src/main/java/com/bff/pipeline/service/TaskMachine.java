package com.bff.pipeline.service;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.utils.TaskKnobs;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Advances ONE task one step (ADR-016 §2, §6). The current task is handed in by {@link PipelineEngine};
 * this class owns the per-task transition but delegates the type-specific work to the task's
 * {@link TaskType} (resolved by name through {@link TaskTypeRegistry}), so it has no {@code switch} on
 * the kind of task.
 *
 * <pre>
 *   BLOCKED      → unblock → READY                         (predecessor reached DONE)
 *   READY        → type.attempt (idempotent dispatch)      → IN_PROGRESS
 *   IN_PROGRESS  → type.check → Succeeded → DONE
 *                              Pending   → reschedule
 *                              Failed    → retry-or-fail (or fail outright if not retryable)
 * </pre>
 *
 * <p>If a task's stored name has no registered type, the task is no longer defined and is failed with
 * {@code UNKNOWN_TASK} (ADR-016 §2) — a business value, which fails the pipeline like any terminal task.
 * Unblocking flips a BLOCKED task to READY once its predecessor is DONE, making it dispatchable on the
 * next advance.
 *
 * <p><b>Invariants.</b> Dispatch is idempotent (ADR-016 §5): a crash before the IN_PROGRESS save is
 * healed by a re-dispatch on the next advance. On a retry the job id is cleared and {@code nextCheckAt}
 * nulled so the fresh re-dispatch is due immediately. An attempt's observation row is always ended
 * before the task terminalizes — so none is left IN_PROGRESS behind a terminal task — and before
 * {@code failCount} is incremented, so it lands on the right {@code attempt_no}. A non-retryable failure
 * (e.g. an expired TTL, whose wait window is gone) fails the task outright with no further attempt.
 *
 * <b>Exception strategy:</b> a {@link TaskType} call into {@link InfraManagerClient} that fails throws; this class
 * is the single boundary that catches those and turns them into a retry or a persisted {@code ErrorCode}
 * ({@link InfraManagerClient.CallTimeoutException} → CALL_TIMEOUT, other failures → CHECK_ERROR,
 * {@link InfraManagerClient.CallInterruptedException} rethrown as fail-fast). Business outcomes are never
 * exceptions; they are {@code ErrorCode} values written to the row. See {@code docs/exception-strategy.md}.
 */
@Component
public class TaskMachine {

    private final TaskTypeRegistry taskTypes;
    private final TaskRepository tasks;
    private final Observations observations;
    private final PipelineSettings settings;
    private final Clock clock;

    public TaskMachine(TaskTypeRegistry taskTypes, TaskRepository tasks, Observations observations,
            PipelineSettings settings, Clock clock) {
        this.taskTypes = taskTypes;
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
            case DONE, FAILED, CANCELLED -> { }
        }
    }

    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        tasks.save(task);
    }

    private void dispatch(String target, Task task) {
        TaskType type = taskTypes.find(task.getTaskName()).orElse(null);
        if (type == null) {
            failUndefined(task);
            return;
        }
        observations.beginAttempt(task);
        try {
            type.attempt(target, task);
        } catch (InfraManagerClient.CallInterruptedException exception) {
            throw exception;
        } catch (InfraManagerClient.CallTimeoutException exception) {
            retryOrFail(task, ErrorCode.CALL_TIMEOUT);
            return;
        } catch (RuntimeException exception) {
            retryOrFail(task, ErrorCode.CHECK_ERROR);
            return;
        }
        if (task.getJobId() != null) {
            observations.recordJobId(task, task.getJobId());
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(clock.instant());
        task.setNextCheckAt(clock.instant());
        tasks.save(task);
    }

    private void poll(String target, Task task) {
        TaskType type = taskTypes.find(task.getTaskName()).orElse(null);
        if (type == null) {
            failUndefined(task);
            return;
        }
        TaskProgress progress;
        try {
            progress = type.check(target, task);
        } catch (InfraManagerClient.CallInterruptedException exception) {
            throw exception;
        } catch (InfraManagerClient.CallTimeoutException exception) {
            observations.recordCheck(task, CheckSignal.CALL_TIMEOUT);
            retryOrFail(task, ErrorCode.CALL_TIMEOUT);
            return;
        } catch (RuntimeException exception) {
            observations.recordCheck(task, CheckSignal.API_ERROR);
            retryOrFail(task, ErrorCode.CHECK_ERROR);
            return;
        }
        apply(task, progress);
    }

    private void apply(Task task, TaskProgress progress) {
        switch (progress) {
            case TaskProgress.Succeeded ignored -> complete(task);
            case TaskProgress.Pending pending -> {
                observations.recordCheck(task, pending.observed());
                reschedule(task, TaskKnobs.pollingInterval(task, settings));
            }
            case TaskProgress.Failed failed -> {
                if (failed.retryable()) {
                    retryOrFail(task, failed.reason());
                } else {
                    observations.endAttempt(task, TaskStatus.FAILED, failed.reason());
                    fail(task, failed.reason());
                }
            }
        }
    }

    private void failUndefined(Task task) {
        observations.endAttempt(task, TaskStatus.FAILED, ErrorCode.UNKNOWN_TASK);
        fail(task, ErrorCode.UNKNOWN_TASK);
    }

    private void retryOrFail(Task task, ErrorCode reason) {
        observations.endAttempt(task, TaskStatus.FAILED, reason);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskKnobs.maxFailCount(task, settings)) {
            fail(task, reason);
            return;
        }
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        task.setJobId(null);
        task.setNextCheckAt(null);
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
