package com.bff.pipeline.exception;

import com.bff.pipeline.model.HttpExchange;

/** 외부 호출 실패의 재시도 정책과 실제 응답을 전달한다. 응답 본문은 로그 메시지에 포함하지 않는다. */
public final class CallFailedException extends RuntimeException {
    private final boolean retryable;
    private final HttpExchange exchange;

    public CallFailedException(String message) { this(message, true, null); }
    public CallFailedException(String message, boolean retryable, HttpExchange exchange) {
        super(message);
        this.retryable = retryable;
        this.exchange = exchange;
    }
    public boolean retryable() { return retryable; }
    public HttpExchange exchange() { return exchange; }
}
