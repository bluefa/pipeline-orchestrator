package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 실행(P10) 요청 본문에 필수 인자인 {@code type}(INSTALL/DELETE)이 빠진 경우다. 400 Bad Request + code
 * {@code ORCHESTRATION_PIPELINE_TYPE_REQUIRED}로 매핑된다.
 */
public class MissingPipelineTypeException extends OrchestrationException {

    public MissingPipelineTypeException() {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.PIPELINE_TYPE_REQUIRED, "type must not be null");
    }
}
