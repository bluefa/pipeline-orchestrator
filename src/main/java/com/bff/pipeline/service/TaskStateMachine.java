package com.bff.pipeline.service;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.utils.TaskSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * ADR-021 phase-B: {@link StepRunner}가 트랜잭션 밖에서 계산한 {@link StepOutcome}을 tx2({@link StepReporter})
 * 안에서 관리 태스크에 적용한다(ADR-016 §2, §6 태스크 전환을 소유). 외부 호출은 이 클래스에 없다 —
 * phase-A가 닫힌 어휘(InfraManager 호출 실패)를 번역해 {@code StepOutcome}으로 넘기면, 이 클래스는 그것을
 * 태스크 상태 전환으로 매핑할 뿐이다.
 *
 * <pre>
 *   BLOCKED      → Unblock      → READY
 *   READY        → Dispatched   → IN_PROGRESS  (beginAttempt + recordResponse 포함)
 *   IN_PROGRESS  → Pending      → reschedule(pollingInterval)
 *                  Succeeded    → DONE
 *                  Failed       → retryOrFail / failOutright
 *                  CallFailure  → recordCheck(poll phase) + retryOrFail
 *   any          → UnknownTask  → FAILED(UNKNOWN_TASK)
 * </pre>
 *
 * <p>유일한 진입점은 {@link #applyOutcome(Task, StepOutcome)}이며 {@link StepReporter}가 tx2 안에서 호출한다.
 *
 * <p><b>불변식.</b> 디스패치는 멱등적이다(ADR-016 §5). 재시도 시 {@code failCount}가 증가하기 전에 시도가
 * 종료 처리되어 정확한 {@code attempt_number}에 기록된다. 재시도는 {@code nextCheckAt}을
 * {@code now + pollingInterval}로 설정한다 — ADR-021 claim 루프에서 즉시 재디스패치가 InfraManager를
 * 난타(hammer)하지 않도록 하는 의도적 케이던스이다(재디스패치는 멱등이므로 안전).
 *
 * <p><b>예외 전략:</b> 외부 호출 실패의 {@code ErrorCode} 변환은 {@link StepRunner}가 phase-A 경계에서
 * 수행한다. 비즈니스 결과는 절대 예외가 아니며 행에 기록되는 {@code ErrorCode} 값이다
 * ({@code docs/exception-strategy.md} 참조).
 */
@Component
public class TaskStateMachine {

    private final TaskRepository tasks;
    private final ObservationRecorder observationRecorder;
    private final PipelineSettings settings;
    private final Clock clock;

    public TaskStateMachine(TaskRepository tasks, ObservationRecorder observationRecorder,
            PipelineSettings settings, Clock clock) {
        this.tasks = tasks;
        this.observationRecorder = observationRecorder;
        this.settings = settings;
        this.clock = clock;
    }

    public void applyOutcome(Task task, StepOutcome outcome) {
        if (outcome.dispatchPhase()) observationRecorder.beginAttempt(task);
        switch (outcome) {
            case StepOutcome.Unblock ignored -> unblock(task);
            case StepOutcome.Dispatched dispatched -> markInProgress(task, dispatched.dispatchResult());
            case StepOutcome.Pending pending -> recordPendingAndReschedule(task, pending.observed());
            case StepOutcome.Succeeded ignored -> complete(task);
            case StepOutcome.Failed failed -> applyFailure(task, failed.reason(), failed.retryable());
            case StepOutcome.CallFailure callFailure -> {
                if (!callFailure.dispatch()) observationRecorder.recordCheck(task, callFailure.signal());
                retryOrFail(task, callFailure.reason());
            }
            case StepOutcome.UnknownTask ignored -> failOutright(task, ErrorCode.UNKNOWN_TASK);
        }
    }

    private void applyFailure(Task task, ErrorCode reason, boolean retryable) {
        if (retryable) retryOrFail(task, reason);
        else failOutright(task, reason);
    }

    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        tasks.save(task);
    }

    private void markInProgress(Task task, DispatchResult dispatchResult) {
        if (dispatchResult instanceof DispatchResult.WithResponse withResponse) {
            observationRecorder.recordResponse(task, withResponse.response());
        }
        Instant now = clock.instant();
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(now);
        task.setNextCheckAt(now);
        tasks.save(task);
    }

    private void recordPendingAndReschedule(Task task, CheckSignal observed) {
        observationRecorder.recordCheck(task, observed);
        reschedule(task, TaskSettings.resolvePollingInterval(task, settings));
    }

    private void failOutright(Task task, ErrorCode reason) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason);
        fail(task, reason);
    }

    private void retryOrFail(Task task, ErrorCode reason) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskSettings.resolveMaxFailCount(task, settings)) {
            fail(task, reason);
            return;
        }
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        task.setNextCheckAt(clock.instant().plus(TaskSettings.resolvePollingInterval(task, settings)));
        tasks.save(task);
    }

    private void complete(Task task) {
        task.setStatus(TaskStatus.DONE);
        task.setFinishedAt(clock.instant());
        tasks.save(task);
        observationRecorder.endAttempt(task, TaskStatus.DONE, null);
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
