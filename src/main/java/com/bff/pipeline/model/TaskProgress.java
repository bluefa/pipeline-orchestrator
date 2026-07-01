package com.bff.pipeline.model;

import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import java.util.Objects;

/**
 * {@link TaskType#check} 폴 한 번의 결과를 담는 봉인(sealed) 타입이며, 엔진이 다음 행동을 정하는 근거가 된다.
 * 비즈니스 실패는 여기서 던져지는 예외가 아니라 데이터다 — 영속화되는 {@code ErrorCode}
 * ({@code docs/exception-strategy.md} 참조). 엔진은 변형(variant)을 완전 열거(exhaustive)해 분기한다.
 *
 * {@code Succeeded}/{@code Pending}/{@code Failed}는 TERRAFORM_JOB이 쓰고, {@code Met}/{@code NotMet}은
 * CONDITION_CHECK 전용이다 — 조건 폴은 매번 원시 payload({@code response})를 남기고, not-met은 실패한 폴로서
 * failCount를 올린다(ADR-016 §6). 두 kind의 경로는 서로 독립적이다.
 */
public sealed interface TaskProgress {

    /** task 작업이 성공적으로 끝났다 → 엔진이 task를 완료 처리한다. */
    record Succeeded() implements TaskProgress {}

    /** 아직 진행 중 — 완료도 실패도 아니다. {@code observed}는 진단 용도로만 남긴다. */
    record Pending(CheckSignal observed) implements TaskProgress {
        public Pending { Objects.requireNonNull(observed, "observed"); }
    }

    /**
     * task가 실패했다. {@code reason}은 영속화되는 실패 원인이고, {@code retryable}은 실패 횟수 상한에
     * 닿기 전이라면 새 재시도를 허용할지를 가른다(잡 실패는 재시도하고, malformed 응답은 재시도하지 않는다).
     */
    record Failed(ErrorCode reason, boolean retryable) implements TaskProgress {
        public Failed { Objects.requireNonNull(reason, "reason"); }
    }

    /**
     * CONDITION_CHECK 폴이 조건 충족을 관찰했다 → 엔진이 task를 완료 처리한다. {@code response}는 그 폴의 원시
     * check payload로 {@code task_attempt.response}에 기록된다.
     */
    record Met(String response) implements TaskProgress {}

    /**
     * CONDITION_CHECK 폴이 조건 미충족을 관찰했다 = 실패한 폴. 엔진이 failCount를 올리고 maxFailCount 전이면
     * polling_interval 뒤 재확인, 닿으면 CONDITION_NOT_MET으로 실패시킨다. {@code response}는 그 폴의 원시
     * check payload다.
     */
    record NotMet(String response) implements TaskProgress {}

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

    static TaskProgress met(String response) {
        return new Met(response);
    }

    static TaskProgress notMet(String response) {
        return new NotMet(response);
    }
}
