package com.bff.pipeline.model;

import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;

/**
 * run 단계(외부 호출, 트랜잭션 밖)의 결과를 write-back 단계(write-back 트랜잭션)가 그대로 적용하도록 담아 나르는 봉인(sealed) 값 타입이다(ADR-021).
 * {@code StepRunner}가 run 단계에서 만들고, {@code StepReporter}가 write-back 단계(write-back 트랜잭션) 안에서 {@code TaskStateMachine}에 넘긴다.
 * 빈(bean)이 아니라 도메인 값 객체이며, 두 트랜잭션 경계를 잇는 정보 전달 매체다.
 *
 * <p>{@code dispatchPhase()}가 true이면 write-back 트랜잭션은 {@code applyOutcome}에 앞서 {@code beginAttempt}를 먼저 기록해야
 * 한다(Dispatched와 dispatch CallFailure — 시도가 이미 시작된 것으로 본다).
 *
 * <p>{@link Dispatched}는 dispatch가 돌려준 {@link DispatchResult}를 담는다. 원시 response를 해석 없이 실어 나를
 * 뿐이고, 형식 해석은 task type 몫이며 write-back 트랜잭션은 {@code task_attempt.response}에 그대로 기록한다(ADR-016 ed97ec0).
 *
 * {@link ApprovalPoll}만 규약이 다르다. 나머지 변형은 run 단계가 내린 판정을 write-back이 그대로
 * 적용하지만, 이 변형은 "판정을 write-back 안에서 내려라"는 위임이다(승인 게이트 ADR §결정 2의 명시적 예외).
 * 승인·만료 경합의 승자는 조건부 UPDATE로 갈리는데, run 단계는 트랜잭션 밖이라 거기서 읽은 값은 이미
 * 낡았을 수 있기 때문이다. 그래서 값에 담을 payload가 없다 — 판정 입력이 DB 행 자체다.
 *
 * <p>정적 팩토리({@code unblock}, {@code dispatched}, {@code pending}, {@code succeeded},
 * {@code failed}, {@code callTimeout}, {@code callFailed}, {@code conditionMet},
 * {@code conditionNotMet}, {@code approvalPoll}, {@code unknownTask})로 만든다.
 */
public sealed interface StepOutcome
        permits StepOutcome.Unblock, StepOutcome.Dispatched, StepOutcome.Pending,
                StepOutcome.Succeeded, StepOutcome.Failed, StepOutcome.CallFailure,
                StepOutcome.ConditionMet, StepOutcome.ConditionNotMet, StepOutcome.ApprovalPoll,
                StepOutcome.UnknownTask {

    /** write-back 트랜잭션이 applyOutcome에 앞서 beginAttempt를 기록해야 하는가. */
    boolean dispatchPhase();

    record Unblock() implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    record Dispatched(DispatchResult dispatchResult) implements StepOutcome {
        public boolean dispatchPhase() { return true; }
    }

    record Pending(CheckSignal observed) implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    record Succeeded() implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    /** {@code detail}은 reason을 보충하는 표시 전용 원인 텍스트다(없으면 null) — task_attempt.failure_detail로 영속된다. */
    record Failed(ErrorCode reason, boolean retryable, String detail) implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    /** {@code detail}은 호출 실패 예외의 메시지다(HTTP status·URL 등) — task_attempt.failure_detail로 영속된다. */
    record CallFailure(ErrorCode reason, CheckSignal signal, boolean dispatch, String detail) implements StepOutcome {
        public boolean dispatchPhase() { return dispatch; }
    }

    /** CONDITION_CHECK 전용: 조건 충족 폴. {@code response}는 그 폴의 원시 check payload(→ task_attempt.response). */
    record ConditionMet(String response) implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    /** CONDITION_CHECK 전용: 조건 미충족 폴 = 실패한 폴. {@code response}는 그 폴의 원시 check payload. */
    record ConditionNotMet(String response) implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    /** APPROVAL 전용: 승인 대기 중인 게이트를 깨웠다 — 어떻게 할지는 write-back 트랜잭션이 승인 행을 보고 정한다. */
    record ApprovalPoll() implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    record UnknownTask() implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    static StepOutcome unblock() { return new Unblock(); }
    static StepOutcome dispatched(DispatchResult dispatchResult) { return new Dispatched(dispatchResult); }
    static StepOutcome pending(CheckSignal signal) { return new Pending(signal); }
    static StepOutcome succeeded() { return new Succeeded(); }
    static StepOutcome failed(ErrorCode reason, boolean retryable, String detail) { return new Failed(reason, retryable, detail); }
    static StepOutcome callTimeout(boolean dispatch, String detail) { return new CallFailure(ErrorCode.CALL_TIMEOUT, CheckSignal.CALL_TIMEOUT, dispatch, detail); }
    static StepOutcome callFailed(boolean dispatch, String detail) { return new CallFailure(ErrorCode.CHECK_ERROR, CheckSignal.API_ERROR, dispatch, detail); }
    static StepOutcome conditionMet(String response) { return new ConditionMet(response); }
    static StepOutcome conditionNotMet(String response) { return new ConditionNotMet(response); }
    static StepOutcome approvalPoll() { return new ApprovalPoll(); }
    static StepOutcome unknownTask() { return new UnknownTask(); }
}
