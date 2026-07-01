package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청이 지목한 task가 존재하지 않거나 해당 pipeline 소속이 아닌 경우다. 404 Not Found + code
 * {@code ORCHESTRATION_TASK_NOT_FOUND}로 매핑된다. 다른 pipeline의 task id를 넘긴 교차 참조도 여기서 막는다.
 */
public class TaskNotFoundException extends OrchestrationException {

    public TaskNotFoundException(long pipelineId, long taskId) {
        super(HttpStatus.NOT_FOUND, OrchestrationErrorCode.TASK_NOT_FOUND,
                "no task " + taskId + " in pipeline " + pipelineId);
    }
}
