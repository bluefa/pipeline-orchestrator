package com.bff.pipeline.model;

import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;

/**
 * ADR-021 phase-A(외부 호출, 트랜잭션 밖) 결과를 phase-B(tx2)가 그대로 적용할 수 있도록 담는 봉인(sealed) 값 타입이다.
 * {@code StepRunner}가 phase-A에서 생성하고, {@code StepReporter}가 phase-B(tx2) 안에서 {@code TaskStateMachine}에 전달한다.
 * 빈(bean)이 아닌 도메인 값 객체이며, 두 트랜잭션 경계 사이의 정보 전달 매체이다.
 *
 * <p>{@code dispatchPhase()} = true이면 tx2가 {@code applyOutcome} 적용 전에 {@code beginAttempt}를
 * 먼저 기록해야 한다(Dispatched + dispatch CallFailure — 시도가 시작된 것으로 간주).
 *
 * <p>{@link Dispatched}는 dispatch가 반환한 {@link DispatchResult}(원시 response를 해석 없이 운반)를 담는다 —
 * 형식 해석은 task type 소관이며, tx2는 {@code task_attempt.response}에 그대로 기록한다(ADR-016 ed97ec0).
 *
 * <p>정적 팩토리({@code unblock}, {@code dispatched}, {@code pending}, {@code succeeded},
 * {@code failed}, {@code callTimeout}, {@code callFailed}, {@code unknownTask})로 생성한다.
 */
public sealed interface StepOutcome
        permits StepOutcome.Unblock, StepOutcome.Dispatched, StepOutcome.Pending,
                StepOutcome.Succeeded, StepOutcome.Failed, StepOutcome.CallFailure, StepOutcome.UnknownTask {

    /** tx2가 applyOutcome 전에 beginAttempt를 기록해야 하는가. */
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

    record Failed(ErrorCode reason, boolean retryable) implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    record CallFailure(ErrorCode reason, CheckSignal signal, boolean dispatch) implements StepOutcome {
        public boolean dispatchPhase() { return dispatch; }
    }

    record UnknownTask() implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    static StepOutcome unblock() { return new Unblock(); }
    static StepOutcome dispatched(DispatchResult dispatchResult) { return new Dispatched(dispatchResult); }
    static StepOutcome pending(CheckSignal signal) { return new Pending(signal); }
    static StepOutcome succeeded() { return new Succeeded(); }
    static StepOutcome failed(ErrorCode reason, boolean retryable) { return new Failed(reason, retryable); }
    static StepOutcome callTimeout(boolean dispatch) { return new CallFailure(ErrorCode.CALL_TIMEOUT, CheckSignal.CALL_TIMEOUT, dispatch); }
    static StepOutcome callFailed(boolean dispatch) { return new CallFailure(ErrorCode.CHECK_ERROR, CheckSignal.API_ERROR, dispatch); }
    static StepOutcome unknownTask() { return new UnknownTask(); }
}
