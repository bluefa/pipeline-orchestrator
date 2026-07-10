package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청이 지목한 (task, attempt, job)의 terraform result 행이 존재하지 않는 경우다. 404 Not Found + code
 * {@code ORCHESTRATION_TERRAFORM_RESULT_NOT_FOUND}로 매핑된다. 행이 있는데 본문만 없는 행은 이
 * 예외가 아니라 {@code content = null}인 200이다 — 두 상태는 운영자 안내가 다르다.
 */
public class TerraformResultNotFoundException extends OrchestrationException {

    public TerraformResultNotFoundException(long taskId, int attemptNumber, String jobId) {
        super(HttpStatus.NOT_FOUND, OrchestrationErrorCode.TERRAFORM_RESULT_NOT_FOUND,
                "no terraform result for task " + taskId + " attempt " + attemptNumber + " job " + jobId);
    }
}
