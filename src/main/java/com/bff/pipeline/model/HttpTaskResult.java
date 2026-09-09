package com.bff.pipeline.model;

import com.bff.pipeline.enums.ErrorCode;
import lombok.Builder;

/**
 * HTTP 작업의 성공 또는 업무 실패를 전달한다. 외부 호출 전 입력 오류는 응답 없이 표현하고, 응답 유무와
 * 재시도 가능 여부를 분리한다. 입력 참조는 등록에 실제 사용한 Task별 스냅샷을 추적한다.
 */
@Builder
public record HttpTaskResult(boolean success, ErrorCode errorCode, boolean retryable, String detail,
        HttpExchange exchange, Long confirmationInputId) {
    public HttpTaskResult {
        if (success ? errorCode != null || retryable : errorCode == null) {
            throw new IllegalArgumentException("HTTP result success and error fields disagree");
        }
    }

    public static HttpTaskResult succeeded(HttpExchange exchange, Long inputId) {
        return builder().success(true).exchange(exchange).confirmationInputId(inputId).build();
    }

    public static HttpTaskResult failed(ErrorCode reason, String detail) {
        return builder().errorCode(reason).detail(detail).build();
    }
}
