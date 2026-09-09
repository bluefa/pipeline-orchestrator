package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/** 공통 설치 Task 요청의 실행 가용성·옵션 계약 위반을 안정적인 API 오류로 반환한다. */
public final class InstallationRequestException extends OrchestrationException {
    private InstallationRequestException(OrchestrationErrorCode code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
    public static InstallationRequestException unavailable(String name) {
        return new InstallationRequestException(OrchestrationErrorCode.OPERATION_UNAVAILABLE, "operation unavailable: " + name);
    }
    public static InstallationRequestException invalidOption() {
        return new InstallationRequestException(OrchestrationErrorCode.INVALID_PARAMETER,
                "apply_nlb_security_group is only supported by the AWS confirmation input task");
    }
}
