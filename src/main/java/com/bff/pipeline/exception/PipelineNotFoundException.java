package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청이 지목한 pipeline이 존재하지 않는 경우다. 404 Not Found + code {@code ORCHESTRATION_PIPELINE_NOT_FOUND}로
 * 매핑된다. 입력 자체는 유효하되 대상이 없을 뿐이라 {@link MissingPipelineIdException}(400)과 구분한다.
 */
public class PipelineNotFoundException extends OrchestrationException {

    public PipelineNotFoundException(long pipelineId) {
        super(HttpStatus.NOT_FOUND, "ORCHESTRATION_PIPELINE_NOT_FOUND", "no pipeline " + pipelineId);
    }
}
