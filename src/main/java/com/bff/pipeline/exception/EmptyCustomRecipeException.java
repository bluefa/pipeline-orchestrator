package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * custom recipe 실행 요청에 task가 하나도 없다(LIN-18). custom 엔드포인트는 최소 한 개의 task를 요구한다 —
 * 400 Bad Request + code {@code ORCHESTRATION_CUSTOM_TASKS_REQUIRED}로 매핑된다.
 */
public class EmptyCustomRecipeException extends OrchestrationException {

    public EmptyCustomRecipeException() {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.CUSTOM_TASKS_REQUIRED,
                "custom recipe requires at least one task");
    }
}
