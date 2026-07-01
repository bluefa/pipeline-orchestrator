package com.bff.pipeline.exception;

/**
 * InfraManager 호출 하나가 호출별 타임아웃을 넘겼을 때 던진다(→ {@code ErrorCode.CALL_TIMEOUT}).
 *
 * <p>이 셋({@link CallTimeoutException}/{@link CallInterruptedException}/{@link CallFailedException})은 InfraManager
 * 전송 경계의 닫힌 어휘다 — HTTP로 매핑되는 {@code OrchestrationException}이 아니라, 도메인({@code StepRunner})이
 * 잡아 영속 {@code ErrorCode} 값으로 변환하는 전송 실패 신호다. 자세한 내용은 {@code docs/exception-strategy.md} 참조.
 */
public final class CallTimeoutException extends RuntimeException {
    public CallTimeoutException() {
        super("InfraManager call exceeded the per-call timeout");
    }
}
