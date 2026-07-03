package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * custom recipe 요청의 Task 설명이 허용 길이(100자)를 넘겼다(LIN-18). 400 Bad Request + code
 * {@code ORCHESTRATION_TASK_DESCRIPTION_TOO_LONG}로 매핑된다.
 */
public class TaskDescriptionTooLongException extends OrchestrationException {

    public TaskDescriptionTooLongException(String taskName, int length, int maxLength) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.TASK_DESCRIPTION_TOO_LONG,
                "description for task " + taskName + " is " + length + " chars, exceeds max " + maxLength);
    }
}
