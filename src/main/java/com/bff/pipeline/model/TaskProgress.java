package com.bff.pipeline.model;

import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import java.util.Objects;

/**
 * {@code TaskType.check} 폴 한 번의 결과를 나타내는 봉인(sealed) 결과 타입이며,
 * 엔진이 다음 행동의 근거로 삼는 값이다. 비즈니스 실패는 여기서 데이터이며 —
 * 영속화된 {@code ErrorCode} — 절대 던져지는 예외가 아니다({@code docs/exception-strategy.md} 참조).
 * 엔진은 세 가지 변형(variant)을 완전 열거(exhaustive)하여 분기한다.
 */
public sealed interface TaskProgress {

    /** task 작업이 성공적으로 완료되었다 → 엔진이 task를 완료 처리한다. */
    record Succeeded() implements TaskProgress {}

    /** 아직 진행 중 — 완료되지도 실패하지도 않았다. {@code observed}는 진단 목적으로만 기록된다. */
    record Pending(CheckSignal observed) implements TaskProgress {
        public Pending { Objects.requireNonNull(observed, "observed"); }
    }

    /**
     * task가 실패했다. {@code reason}은 영속화된 실패 원인이고, {@code retryable}은 실패 횟수
     * 상한에 도달하기 전에 새 재시도가 허용되는지 여부를 나타낸다(잡 실패는 재시도하고,
     * 만료된 TTL은 재시도하지 않는다).
     */
    record Failed(ErrorCode reason, boolean retryable) implements TaskProgress {
        public Failed { Objects.requireNonNull(reason, "reason"); }
    }

    TaskProgress SUCCEEDED = new Succeeded();

    static TaskProgress pending(CheckSignal observed) {
        return new Pending(observed);
    }

    static TaskProgress failedRetryable(ErrorCode reason) {
        return new Failed(reason, true);
    }

    static TaskProgress failedTerminal(ErrorCode reason) {
        return new Failed(reason, false);
    }
}
