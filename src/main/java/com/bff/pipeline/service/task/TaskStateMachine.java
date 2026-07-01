package com.bff.pipeline.service.task;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;

import com.bff.pipeline.config.PipelineSettings;
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
 * ADR-021 phase-B: {@link StepRunner}가 트랜잭션 밖에서 계산해 둔 {@link StepOutcome}을 tx2({@link StepReporter}) 안에서
 * 관리 태스크에 적용한다(ADR-016 §2, §6의 태스크 전환을 소유한다). 이 클래스에는 외부 호출이 전혀 없다 — phase-A가 닫힌
 * 어휘(InfraManager 호출 실패)로 번역해 {@code StepOutcome}으로 넘겨주면, 여기서는 그것을 태스크 상태 전환으로 매핑하기만 한다.
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
 * <p>유일한 진입점은 {@link #applyOutcome(Task, StepOutcome)}이고, {@link StepReporter}가 tx2 안에서 호출한다.
 *
 * <p><b>불변식.</b> 디스패치는 멱등적이다(ADR-016 §5). 재시도할 때는 {@code failCount}가 늘기 전에 시도를 먼저 종료 처리해
 * 정확한 {@code attempt_number}에 기록한다. 재시도는 {@code nextCheckAt}을 {@code now + pollingInterval}로 잡는데,
 * ADR-021 claim 루프에서 곧바로 재디스패치가 InfraManager를 난타(hammer)하지 않도록 일부러 둔 케이던스다(재디스패치는 멱등이라 안전하다).
 *
 * <p><b>예외 전략.</b> 외부 호출 실패를 {@code ErrorCode}로 바꾸는 일은 {@link StepRunner}가 phase-A 경계에서 처리한다.
 * 비즈니스 결과는 결코 예외가 아니라 행에 기록되는 {@code ErrorCode} 값이다({@code docs/exception-strategy.md} 참조).
 */
@Component
public class TaskStateMachine {

    private final TaskRepository taskRepository;
    private final ObservationRecorder observationRecorder;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public TaskStateMachine(TaskRepository taskRepository, ObservationRecorder observationRecorder,
            PipelineSettings pipelineSettings, Clock clock) {
        this.taskRepository = taskRepository;
        this.observationRecorder = observationRecorder;
        this.pipelineSettings = pipelineSettings;
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
        taskRepository.save(task);
    }

    private void markInProgress(Task task, DispatchResult dispatchResult) {
        if (dispatchResult instanceof DispatchResult.WithResponse withResponse) {
            observationRecorder.recordResponse(task, withResponse.response());
        }
        Instant now = clock.instant();
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(now);
        task.setNextCheckAt(now);
        taskRepository.save(task);
    }

    private void recordPendingAndReschedule(Task task, CheckSignal observed) {
        observationRecorder.recordCheck(task, observed);
        reschedule(task, TaskSettings.resolvePollingInterval(task, pipelineSettings));
    }

    private void failOutright(Task task, ErrorCode reason) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason);
        fail(task, reason);
    }

    private void retryOrFail(Task task, ErrorCode reason) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskSettings.resolveMaxFailCount(task, pipelineSettings)) {
            fail(task, reason);
            return;
        }
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        task.setNextCheckAt(clock.instant().plus(TaskSettings.resolvePollingInterval(task, pipelineSettings)));
        taskRepository.save(task);
    }

    private void complete(Task task) {
        task.setStatus(TaskStatus.DONE);
        task.setFinishedAt(clock.instant());
        taskRepository.save(task);
        observationRecorder.endAttempt(task, TaskStatus.DONE, null);
    }

    private void fail(Task task, ErrorCode reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setErrorCode(reason);
        task.setFinishedAt(clock.instant());
        taskRepository.save(task);
    }

    private void reschedule(Task task, Duration after) {
        task.setNextCheckAt(clock.instant().plus(after));
        taskRepository.save(task);
    }
}
