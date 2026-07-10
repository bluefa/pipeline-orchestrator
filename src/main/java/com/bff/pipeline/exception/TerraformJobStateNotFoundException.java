package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청이 지목한 (task, attempt, job)의 terraform job 상태 행이 존재하지 않는 경우다. 404 Not Found + code
 * {@code ORCHESTRATION_TERRAFORM_JOB_STATE_NOT_FOUND}로 매핑된다. 아직 한 번도 폴되지 않은 job이거나 잘못된
 * job id면 행이 없다 — 운영자는 attempt의 {@code response} job id 목록과 대조해 구분한다.
 */
public class TerraformJobStateNotFoundException extends OrchestrationException {

    public TerraformJobStateNotFoundException(long taskId, int attemptNumber, String jobId) {
        super(HttpStatus.NOT_FOUND, OrchestrationErrorCode.TERRAFORM_JOB_STATE_NOT_FOUND,
                "no terraform job state for task " + taskId + " attempt " + attemptNumber + " job " + jobId);
    }
}
