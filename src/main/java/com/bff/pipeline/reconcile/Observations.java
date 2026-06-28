package com.bff.pipeline.reconcile;

import com.bff.pipeline.domain.ErrorCode;
import com.bff.pipeline.domain.Task;
import com.bff.pipeline.domain.TaskAttempt;
import com.bff.pipeline.domain.TaskCheck;
import com.bff.pipeline.domain.TaskStatus;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single writer of the write-only observation tables ({@code task_attempt}, {@code task_check};
 * ADR-016 §3). It is debuggability only: the reconciler never reads what it writes, and it is
 * resilient to the common case (a missing attempt row is a no-op). It rides the reconcile transaction
 * (not a separate {@code REQUIRES_NEW} tx — the async-observation split was rejected); a write failure
 * rolls back and retries the whole tick, never corrupting state. See {@code docs/exception-strategy.md}.
 *
 * <p>The current attempt is identified by {@code (task.id, attemptNo = task.failCount + 1)} — stable
 * for the whole attempt because {@code failCount} only changes when an attempt ends.
 */
@Component
public class Observations {

    /** The poll observations worth counting; the terminal DONE/FAILED outcome lives on the attempt. */
    public enum CheckSignal {
        RUNNING,
        NOT_MET,
        API_ERROR,
        CALL_TIMEOUT
    }

    private final TaskAttemptRepository attempts;
    private final TaskCheckRepository checks;
    private final Clock clock;

    public Observations(TaskAttemptRepository attempts, TaskCheckRepository checks, Clock clock) {
        this.attempts = attempts;
        this.checks = checks;
        this.clock = clock;
    }

    /** A new attempt begins (a task entering its dispatch). */
    public void beginAttempt(Task task) {
        attempts.save(TaskAttempt.builder()
                .taskId(task.getId())
                .attemptNo(attemptNo(task))
                .jobId(task.getJobId())
                .status(TaskStatus.IN_PROGRESS)
                .startedAt(clock.instant())
                .build());
    }

    /** Record the job id once a TERRAFORM dispatch returns it. */
    public void recordJobId(Task task, String jobId) {
        currentAttempt(task).ifPresent(attempt -> {
            attempt.setJobId(jobId);
            attempts.save(attempt);
        });
    }

    /** Summarize one poll observation into the attempt's check row (created on first use, then updated). */
    public void recordCheck(Task task, CheckSignal signal) {
        Optional<TaskAttempt> attempt = currentAttempt(task);
        if (attempt.isEmpty()) {
            return;
        }
        TaskCheck check = checks.findByTaskAttemptId(attempt.get().getId())
                .orElseGet(() -> TaskCheck.builder().taskAttemptId(attempt.get().getId()).build());
        check.setCallCount(check.getCallCount() + 1);
        switch (signal) {
            case NOT_MET -> check.setNotMetCount(check.getNotMetCount() + 1);
            case API_ERROR -> check.setApiErrorCount(check.getApiErrorCount() + 1);
            case CALL_TIMEOUT -> check.setCallTimeoutCount(check.getCallTimeoutCount() + 1);
            case RUNNING -> { /* counted by callCount only */ }
        }
        check.setLastExternalStatus(signal.name());
        check.setLastCheckedAt(clock.instant());
        checks.save(check);
    }

    /** The attempt ended; record its outcome. */
    public void endAttempt(Task task, TaskStatus outcome, ErrorCode errorCode) {
        currentAttempt(task).ifPresent(attempt -> {
            attempt.setStatus(outcome);
            attempt.setErrorCode(errorCode);
            attempt.setFinishedAt(clock.instant());
            attempts.save(attempt);
        });
    }

    private Optional<TaskAttempt> currentAttempt(Task task) {
        return attempts.findByTaskIdAndAttemptNo(task.getId(), attemptNo(task));
    }

    private static int attemptNo(Task task) {
        return task.getFailCount() + 1;
    }
}
