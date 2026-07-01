package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * API 경계 예외의 공통 상위 타입이다. 각 예외는 매핑될 HTTP {@link HttpStatus}와 안정적인 오류 {@code code}를
 * 함께 들고 있어, {@code GlobalAdvice}가 타입별 분기 없이 status와 code를 그대로 응답으로 옮긴다. 도메인 규칙
 * 위반을 값으로 나르는 {@code enums.ErrorCode}와 달리, 이건 요청·리소스 계약 위반이다.
 */
public abstract class OrchestrationException extends RuntimeException {

    private final HttpStatus status;
    private final OrchestrationErrorCode errorCode;

    protected OrchestrationException(HttpStatus status, OrchestrationErrorCode errorCode, String message) {
        this(status, errorCode, message, null);
    }

    protected OrchestrationException(HttpStatus status, OrchestrationErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return errorCode.code();
    }
}
