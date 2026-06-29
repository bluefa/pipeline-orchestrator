package com.bff.pipeline.service;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.utils.TaskSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * ADR-021 phase-B: {@link StepRunner}가 계산한 {@link StepOutcome}을 tx2({@link StepReporter}) 안에서
 * 관리 태스크에 적용한다. 외부 호출은 이 클래스에 없다 — phase-A({@link StepRunner})가 닫힌 어휘를
 * 번역하여 {@code StepOutcome}으로 전달하면, 이 클래스는 그것을 태스크 상태 전환으로 매핑한다.
 *
 * <pre>
 *   BLOCKED      → Unblock      → READY
 *   READY        → Dispatched   → IN_PROGRESS  (beginAttempt 포함)
 *   IN_PROGRESS  → Pending      → reschedule
 *                  Succeeded    → DONE
 *                  Failed       → retryOrFail / failOutright
 *                  CallFailure  → recordCheck(poll phase) + retryOrFail
 *   any          → UnknownTask  → FAILED(UNKNOWN_TASK)
 * </pre>
 *
 * <p>진입점은 {@link #applyOutcome(Task, StepOutcome)} 하나뿐이다.
 * {@link StepReporter}가 tx2 안에서 호출한다.
 */
@Component
public class TaskMachine {

    private final TaskRepository tasks;
    private final Observations observations;
    private final PipelineSettings settings;
    private final Clock clock;

    public TaskMachine(TaskRepository tasks, Observations observations, PipelineSettings settings, Clock clock) {
        this.tasks = tasks;
        this.observations = observations;
        this.settings = settings;
        this.clock = clock;
    }

    public void applyOutcome(Task task, StepOutcome outcome) {
        if (outcome.dispatchPhase()) observations.beginAttempt(task);
        switch (outcome) {
            case StepOutcome.Unblock ignored -> unblock(task);
            case StepOutcome.Dispatched d -> { task.setJobId(d.jobId()); markInProgress(task); }
            case StepOutcome.Pending p -> recordPendingAndReschedule(task, new TaskProgress.Pending(p.observed()));
            case StepOutcome.Succeeded ignored -> complete(task);
            case StepOutcome.Failed f -> {
                if (f.retryable()) retryOrFail(task, f.reason());
                else failOutright(task, f.reason());
            }
            case StepOutcome.CallFailure cf -> {
                if (!cf.dispatch()) observations.recordCheck(task, cf.signal());
                retryOrFail(task, cf.reason());
            }
            case StepOutcome.UnknownTask ignored -> failUnknownTask(task);
        }
    }

    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        tasks.save(task);
    }

    private void markInProgress(Task task) {
        if (task.getJobId() != null) {
            observations.recordJobId(task, task.getJobId());
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        Instant now = clock.instant();
        task.setStartedAt(now);
        task.setNextCheckAt(now);
        tasks.save(task);
    }

    private void recordPendingAndReschedule(Task task, TaskProgress.Pending pending) {
        observations.recordCheck(task, pending.observed());
        reschedule(task, TaskSettings.resolvePollingInterval(task, settings));
    }

    private void failUnknownTask(Task task) {
        failOutright(task, ErrorCode.UNKNOWN_TASK);
    }

    private void failOutright(Task task, ErrorCode reason) {
        observations.endAttempt(task, TaskStatus.FAILED, reason);
        fail(task, reason);
    }

    private void retryOrFail(Task task, ErrorCode reason) {
        observations.endAttempt(task, TaskStatus.FAILED, reason);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskSettings.resolveMaxFailCount(task, settings)) {
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
