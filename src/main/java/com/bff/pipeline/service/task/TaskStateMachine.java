package com.bff.pipeline.service.task;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.enums.ApprovalStatus;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.TaskApprovalRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.utils.TaskSettingsResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ADR-021 write-back 단계: {@link StepRunner}가 트랜잭션 밖에서 계산해 둔 {@link StepOutcome}을 write-back 트랜잭션({@link StepReporter}) 안에서
 * 관리 태스크에 적용한다(ADR-016 §2, §6의 태스크 전환을 소유한다). 이 클래스에는 외부 호출이 전혀 없다 — run 단계가 닫힌
 * 어휘(InfraManager 호출 실패)로 번역해 {@code StepOutcome}으로 넘겨주면, 여기서는 그것을 태스크 상태 전환으로 매핑하기만 한다.
 *
 * <pre>
 *   BLOCKED        → Unblock      → READY
 *   READY          → Dispatched   → IN_PROGRESS  (beginAttempt + recordResponse 포함)
 *                                 → AWAIT_APPROVAL (승인 게이트 — 요청 행 생성까지 같은 트랜잭션)
 *   IN_PROGRESS    → Pending      → reschedule(pollingInterval)
 *                    Succeeded    → DONE
 *                    Failed       → retryOrFail / failOutright
 *                    CallFailure  → recordCheck(poll phase) + retryOrFail
 *                    ConditionMet → recordResponse + recordCheck(MET) + DONE
 *                    ConditionNotMet → recordResponse + recordCheck(NOT_MET) + retryOrFail(CONDITION_NOT_MET)
 *   AWAIT_APPROVAL → ApprovalPoll → DONE(승인) / FAILED(만료) / 대기 유지(이른 웨이크업)
 *   any            → UnknownTask  → FAILED(UNKNOWN_TASK)
 * </pre>
 *
 * 승인 게이트만 판정 위치가 다르다. 다른 전이는 run 단계가 내린 결론을 여기서 적용하기만 하지만,
 * {@code ApprovalPoll}은 결론 없이 도착한다 — 승인과 만료 중 누가 이겼는지는 조건부 UPDATE로 갈리고, 그
 * UPDATE는 반드시 이 트랜잭션 안에서 실행돼야 하기 때문이다(승인 게이트 ADR §결정 2·3).
 *
 * <p>유일한 진입점은 {@link #applyOutcome(Task, StepOutcome)}이고, {@link StepReporter}가 write-back 트랜잭션 안에서 호출한다.
 *
 * <p><b>불변식.</b> 디스패치는 멱등적이다(ADR-016 §5). 재시도할 때는 {@code failCount}가 늘기 전에 시도를 먼저 종료 처리해
 * 정확한 {@code attempt_number}에 기록한다. 재시도는 {@code nextCheckAt}을 {@code now + pollingInterval}로 잡는데,
 * ADR-021 claim 루프에서 곧바로 재디스패치가 InfraManager를 난타(hammer)하지 않도록 일부러 둔 케이던스다(재디스패치는 멱등이라 안전하다).
 *
 * <p><b>예외 전략.</b> 외부 호출 실패를 {@code ErrorCode}로 바꾸는 일은 {@link StepRunner}가 run 단계 경계에서 처리한다.
 * 비즈니스 결과는 결코 예외가 아니라 행에 기록되는 {@code ErrorCode} 값이다({@code docs/exception-strategy.md} 참조).
 */
@Component
@RequiredArgsConstructor
public class TaskStateMachine {

    private final TaskRepository taskRepository;
    private final TaskApprovalRepository taskApprovalRepository;
    private final ObservationRecorder observationRecorder;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public void applyOutcome(Task task, StepOutcome outcome) {
        if (outcome.dispatchPhase()) observationRecorder.beginAttempt(task);
        switch (outcome) {
            case StepOutcome.Unblock ignored -> unblock(task);
            case StepOutcome.Dispatched dispatched -> applyDispatch(task, dispatched.dispatchResult());
            case StepOutcome.ApprovalPoll ignored -> resolveApproval(task);
            case StepOutcome.Pending pending -> recordPendingAndReschedule(task, pending.observed());
            case StepOutcome.Succeeded ignored -> complete(task);
            case StepOutcome.Failed failed -> applyFailure(task, failed.reason(), failed.retryable(), failed.detail());
            case StepOutcome.CallFailure callFailure -> {
                if (!callFailure.dispatch()) observationRecorder.recordCheck(task, callFailure.signal());
                retryOrFail(task, callFailure.reason(), callFailure.detail());
            }
            case StepOutcome.ConditionMet met -> completeCondition(task, met.response());
            case StepOutcome.ConditionNotMet notMet -> retryCondition(task, notMet.response());
            case StepOutcome.UnknownTask ignored -> failOutright(task, ErrorCode.UNKNOWN_TASK);
        }
    }

    /** CONDITION_CHECK 충족 폴: 그 폴의 payload와 MET 관찰을 남기고 task를 완료한다(ADR-016 §6). */
    private void completeCondition(Task task, String response) {
        observationRecorder.recordResponse(task, response);
        observationRecorder.recordCheck(task, CheckSignal.MET);
        complete(task);
    }

    /** CONDITION_CHECK 미충족 폴 = 실패한 폴: payload와 NOT_MET 관찰을 남기고 failCount 예산으로 재시도/실패시킨다. */
    private void retryCondition(Task task, String response) {
        observationRecorder.recordResponse(task, response);
        observationRecorder.recordCheck(task, CheckSignal.NOT_MET);
        retryOrFail(task, ErrorCode.CONDITION_NOT_MET, null);
    }

    private void applyFailure(Task task, ErrorCode reason, boolean retryable, String failureDetail) {
        if (retryable) retryOrFail(task, reason, failureDetail);
        else failOutright(task, reason, failureDetail);
    }

    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        taskRepository.save(task);
    }

    /**
     * dispatch 결과를 태스크 전이로 옮긴다. {@code switch}로 쓰는 이유는 새 결과 종류가 생겼을 때 컴파일러가
     * 여기를 지목하게 하기 위해서다 — {@code instanceof} 한 줄이면 처리되지 않은 종류가 조용히 기본 분기로
     * 흘러 엉뚱한 상태가 된다(승인 대기가 실행 중으로 기록되는 식으로).
     */
    private void applyDispatch(Task task, DispatchResult dispatchResult) {
        switch (dispatchResult) {
            case DispatchResult.WithResponse withResponse -> {
                observationRecorder.recordResponse(task, withResponse.response());
                markInProgress(task);
            }
            case DispatchResult.None ignored -> markInProgress(task);
            case DispatchResult.AwaitApproval gate -> enterApprovalGate(task, gate);
        }
    }

    private void markInProgress(Task task) {
        Instant now = clock.instant();
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(now);
        task.setNextCheckAt(now);
        taskRepository.save(task);
    }

    /**
     * 승인 대기 진입. 요청 행 생성과 상태 전이가 한 트랜잭션이라, 승인 요청 없이 대기 상태로 남거나 그
     * 반대인 어긋난 조합이 생기지 않는다.
     *
     * {@code nextCheckAt}에 만료 시각을 넣는 것이 대기의 핵심이다. 스텝을 마무리하는 쪽이 이 값을
     * 파이프라인의 다음 처리 시각으로 올리므로, 파이프라인은 만료 시각까지 아예 잡히지 않는다 — 승인을
     * 기다리는 동안 실행 자원을 쥐지 않는다는 약속이 여기서 지켜진다. 값을 넣지 않으면 앞 단계가 남긴
     * 과거 시각이 그대로 남아 매 sweep마다 다시 잡힌다.
     */
    private void enterApprovalGate(Task task, DispatchResult.AwaitApproval gate) {
        Instant now = clock.instant();
        taskApprovalRepository.save(TaskApproval.builder()
                .taskId(task.getId())
                .status(ApprovalStatus.REQUESTED)
                .requestedAt(now)
                .expiresAt(gate.expiresAt())
                .build());
        task.setStatus(TaskStatus.AWAIT_APPROVAL);
        task.setStartedAt(now);
        task.setNextCheckAt(gate.expiresAt());
        taskRepository.save(task);
    }

    /**
     * 대기 중인 게이트를 깨웠을 때의 판정이다. 먼저 "지금 만료시킬 수 있는가"를 조건부 UPDATE로 물어보고,
     * 한 행이 바뀌면 이번 사이클이 만료를 확정한 것이라 실패로 닫는다. 0행이면 이미 결정이 났거나 아직
     * 만료 시각이 안 된 것이므로, 행을 다시 읽어 어느 쪽인지 구분한다.
     *
     * 먼저 물어보고 나중에 읽는 순서인 이유는 그 반대가 안전하지 않기 때문이다. 읽고 나서 판단하면
     * 읽은 값과 쓰는 시점 사이에 승인이 끼어들 수 있다 — 조건부 UPDATE가 그 틈을 없앤다.
     *
     * 반려는 여기서 다루지 않는다. 반려는 결정을 기록하면서 파이프라인에 취소 요청을 함께 세우고,
     * 그 요청은 이 판정보다 앞에서 관찰돼 실행 전체를 취소로 닫는다.
     */
    private void resolveApproval(Task task) {
        Instant now = clock.instant();
        if (taskApprovalRepository.expireIfDue(task.getId(), now) == 1) {
            failOutright(task, ErrorCode.APPROVAL_EXPIRED);
            return;
        }
        TaskApproval approval = taskApprovalRepository.findByTaskId(task.getId())
                // 요청 행과 대기 상태는 한 트랜잭션에서 함께 커밋되므로, 대기 중인데 행이 없는 조합은
                // 데이터가 깨졌다는 뜻이다. 넘겨짚지 않고 드러낸다.
                .orElseThrow(() -> corrupted(task, "has no approval row"));
        switch (approval.getStatus()) {
            case APPROVED -> complete(task);
            case EXPIRED -> failOutright(task, ErrorCode.APPROVAL_EXPIRED);
            // 아직 유효한 요청이다 — 만료 시각보다 이르게 깨어난 것이므로 대기를 이어 간다.
            case REQUESTED -> keepAwaitingApproval(task, approval.getExpiresAt());
            // 반려·취소된 요청은 여기 도달할 수 없다. 반려는 결정과 같은 트랜잭션에서 취소 요청 표시를
            // 세우고 그 표시가 이 판정보다 앞에서 관찰되며, 취소는 태스크를 이미 종결시킨 뒤에만 요청을
            // 닫기 때문이다. 그런데도 도달했다면 손으로 행을 고친 것이므로, 대기를 이어 가 조용히
            // 맴돌게 두는 대신 드러낸다 — 만료 시각이 이미 지난 요청이라 그 맴돎은 끝나지 않는다.
            case REJECTED, CANCELLED -> throw corrupted(task,
                    "carries a " + approval.getStatus() + " approval without a pending cancel request");
        }
    }

    private static IllegalStateException corrupted(Task task, String problem) {
        return new IllegalStateException("task " + task.getId() + " awaits approval but " + problem);
    }

    private void keepAwaitingApproval(Task task, Instant expiresAt) {
        task.setNextCheckAt(expiresAt);
        taskRepository.save(task);
    }

    private void recordPendingAndReschedule(Task task, CheckSignal observed) {
        observationRecorder.recordCheck(task, observed);
        reschedule(task, TaskSettingsResolver.resolvePollingInterval(task, pipelineSettings));
    }

    private void failOutright(Task task, ErrorCode reason) {
        failOutright(task, reason, null);
    }

    private void failOutright(Task task, ErrorCode reason, String failureDetail) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason, failureDetail);
        fail(task, reason);
    }

    private void retryOrFail(Task task, ErrorCode reason, String failureDetail) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason, failureDetail);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskSettingsResolver.resolveMaxFailCount(task, pipelineSettings)) {
            fail(task, reason);
            return;
        }
        Instant now = clock.instant();
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(now);
        task.setNextCheckAt(now.plus(TaskSettingsResolver.resolvePollingInterval(task, pipelineSettings)));
        taskRepository.save(task);
    }

    private void complete(Task task) {
        task.setStatus(TaskStatus.DONE);
        task.setFinishedAt(clock.instant());
        taskRepository.save(task);
        observationRecorder.endAttempt(task, TaskStatus.DONE, null, null);
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
