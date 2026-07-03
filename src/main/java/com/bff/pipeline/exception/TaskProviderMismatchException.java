package com.bff.pipeline.exception;

import com.bff.pipeline.enums.CloudProvider;
import org.springframework.http.HttpStatus;

/**
 * custom recipe 요청의 Task가 target의 cloud provider와 다른 provider 소속이다(LIN-18). 400 Bad Request + code
 * {@code ORCHESTRATION_TASK_PROVIDER_MISMATCH}로 매핑된다.
 */
public class TaskProviderMismatchException extends OrchestrationException {

    public TaskProviderMismatchException(String taskName, CloudProvider taskProvider, CloudProvider targetProvider) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.TASK_PROVIDER_MISMATCH,
                "task " + taskName + " is provider " + taskProvider + " but target is provider " + targetProvider);
    }
}
