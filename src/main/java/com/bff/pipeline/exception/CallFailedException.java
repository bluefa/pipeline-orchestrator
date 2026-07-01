package com.bff.pipeline.exception;

/**
 * 그 밖의 모든 InfraManager 호출 실패(HTTP 오류, 거부, 잘못된/빈 응답)에 던진다(→ {@code ErrorCode.CHECK_ERROR}).
 * InfraManager 전송 경계 닫힌 어휘의 하나다({@link CallTimeoutException} 참조).
 */
public final class CallFailedException extends RuntimeException {
    public CallFailedException(String message) {
        super(message);
    }
}
