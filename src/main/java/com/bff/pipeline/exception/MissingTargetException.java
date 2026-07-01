package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 필수 인자인 {@code target}이 빠졌거나 비어 있는 create 요청이다. 외부 provider 조회 전에 검증해 400 Bad Request +
 * code {@code ORCHESTRATION_TARGET_REQUIRED}로 매핑한다.
 */
public class MissingTargetException extends OrchestrationException {

    public MissingTargetException() {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.TARGET_REQUIRED, "target must not be blank");
    }
}
