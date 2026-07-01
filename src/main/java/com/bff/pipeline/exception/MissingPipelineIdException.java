package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 필수 인자인 {@code pipelineId}가 빠진 요청이다. 400 Bad Request + code
 * {@code ORCHESTRATION_PIPELINE_ID_REQUIRED}로 매핑된다.
 */
public class MissingPipelineIdException extends OrchestrationException {

    public MissingPipelineIdException() {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.PIPELINE_ID_REQUIRED, "pipelineId must not be null");
    }
}
