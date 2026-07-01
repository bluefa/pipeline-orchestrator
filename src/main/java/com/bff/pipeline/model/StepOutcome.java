package com.bff.pipeline.model;

import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;

/**
 * phase-A(외부 호출, 트랜잭션 밖)의 결과를 phase-B(tx2)가 그대로 적용하도록 담아 나르는 봉인(sealed) 값 타입이다(ADR-021).
 * {@code StepRunner}가 phase-A에서 만들고, {@code StepReporter}가 phase-B(tx2) 안에서 {@code TaskStateMachine}에 넘긴다.
 * 빈(bean)이 아니라 도메인 값 객체이며, 두 트랜잭션 경계를 잇는 정보 전달 매체다.
 *
 * <p>{@code dispatchPhase()}가 true이면 tx2는 {@code applyOutcome}에 앞서 {@code beginAttempt}를 먼저 기록해야
 * 한다(Dispatched와 dispatch CallFailure — 시도가 이미 시작된 것으로 본다).
 *
 * <p>{@link Dispatched}는 dispatch가 돌려준 {@link DispatchResult}를 담는다. 원시 response를 해석 없이 실어 나를
 * 뿐이고, 형식 해석은 task type 몫이며 tx2는 {@code task_attempt.response}에 그대로 기록한다(ADR-016 ed97ec0).
 *
 * <p>정적 팩토리({@code unblock}, {@code dispatched}, {@code pending}, {@code succeeded},
 * {@code failed}, {@code callTimeout}, {@code callFailed}, {@code unknownTask})로 만든다.
 */
public sealed interface StepOutcome
        permits StepOutcome.Unblock, StepOutcome.Dispatched, StepOutcome.Pending,
                StepOutcome.Succeeded, StepOutcome.Failed, StepOutcome.CallFailure, StepOutcome.UnknownTask {

    /** tx2가 applyOutcome에 앞서 beginAttempt를 기록해야 하는가. */
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
