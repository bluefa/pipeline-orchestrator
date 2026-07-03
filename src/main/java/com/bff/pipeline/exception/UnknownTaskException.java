package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * custom recipe 요청이 참조한 Task 이름이 TaskDefinition 카탈로그에 없다(LIN-18). 400 Bad Request + code
 * {@code ORCHESTRATION_UNKNOWN_TASK}로 매핑된다.
 */
public class UnknownTaskException extends OrchestrationException {

    public UnknownTaskException(String taskName) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.UNKNOWN_TASK,
                "unknown task name: " + taskName);
    }
}
