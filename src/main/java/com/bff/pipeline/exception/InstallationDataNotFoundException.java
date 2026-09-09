package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/** HTTP 응답 또는 Task 입력 행 부재를 통제된 404로 구분한다. 본문이 아직 없는 정상 행과 행 유실은 다르다. */
public final class InstallationDataNotFoundException extends OrchestrationException {
    public InstallationDataNotFoundException(OrchestrationErrorCode code, Long taskId) {
        super(HttpStatus.NOT_FOUND, code, "installation data not found for task " + taskId);
    }
}
