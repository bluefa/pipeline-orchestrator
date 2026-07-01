package com.bff.pipeline.exception;

/**
 * 호출 스레드가 인터럽트됐을 때(예: 종료 신호) 던진다 — 비즈니스 결과가 아니라 fail-fast 런타임 신호다.
 * InfraManager 전송 경계 닫힌 어휘의 하나다({@link CallTimeoutException} 참조).
 */
public final class CallInterruptedException extends RuntimeException {
    public CallInterruptedException() {
        super("InfraManager call interrupted");
    }
}
