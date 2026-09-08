package com.bff.pipeline.model;

import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import lombok.Builder;

/**
 * 트랜잭션 밖 실행 결과를 점유 소유권을 확인한 상태 기록 단계로 전달한다. 외부 호출 결과 자체를 넘기므로
 * 여기서는 DB를 읽거나 쓰지 않는다. Dispatched는 기존 비동기 응답, 추천 입력 준비, HTTP 완료, 테스트 접수를
 * 구분하는 DispatchResult를 담고 한 번의 attempt 생성을 요청한다.
 *
 * HttpCompleted는 같은 입력 Task의 후속 POST 결과이며 기존 attempt를 사용한다. TestConnectionPolled는
 * 특정 실행의 정상 관찰/통신 오류/업무 실패를 담아 같은 attempt에서 기록한다. CallFailure는 엔진이 번역한
 * 오류 코드와 재시도 여부, 실제 HTTP 응답을 보존한다. 인터럽트나 예상하지 못한 코드 오류를 값으로 숨기지 않는다.
 */
public sealed interface StepOutcome
        permits StepOutcome.Unblock, StepOutcome.Dispatched, StepOutcome.Pending,
                StepOutcome.Succeeded, StepOutcome.Failed, StepOutcome.CallFailure,
                StepOutcome.ConditionMet, StepOutcome.ConditionNotMet, StepOutcome.UnknownTask, StepOutcome.HttpCompleted, StepOutcome.TestConnectionPolled {

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

    /** 호출 실패의 짧은 설명과 원문 응답을 분리하며, 재시도 여부는 외부 경계에서 판정한 정책을 유지한다. */
    @Builder
    record CallFailure(ErrorCode reason, CheckSignal signal, boolean dispatch, String detail,
            boolean retryable, HttpExchange exchange) implements StepOutcome {
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

    record HttpCompleted(HttpTaskResult result) implements StepOutcome {
        public boolean dispatchPhase() { return false; }
    }

    record TestConnectionPolled(TestConnectionObservation observation) implements StepOutcome {
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
    static CallFailure callTimeout(boolean dispatch, String detail) {
        return CallFailure.builder().reason(ErrorCode.CALL_TIMEOUT).signal(CheckSignal.CALL_TIMEOUT)
                .dispatch(dispatch).detail(detail).retryable(true).build();
    }
    static StepOutcome callFailed(boolean dispatch, String detail) {
        return CallFailure.builder().reason(ErrorCode.CHECK_ERROR).signal(CheckSignal.API_ERROR)
                .dispatch(dispatch).detail(detail).retryable(true).build();
    }
    static StepOutcome conditionMet(String response) { return new ConditionMet(response); }
    static StepOutcome conditionNotMet(String response) { return new ConditionNotMet(response); }
    static StepOutcome unknownTask() { return new UnknownTask(); }
}
