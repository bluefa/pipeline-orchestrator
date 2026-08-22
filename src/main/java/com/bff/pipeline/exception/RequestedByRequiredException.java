package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 승인 게이트가 있는 레시피로 실행을 만들면서 요청자를 주지 않았다(승인 게이트 ADR §결정 4). 요청자를
 * 모르는 승인 요청은 승인자가 누구에게 묻고 무엇을 근거로 판단할지 알 수 없어 감사가 성립하지 않으므로,
 * 저장 전에 400으로 거절한다.
 */
public class RequestedByRequiredException extends OrchestrationException {

    public RequestedByRequiredException() {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.REQUESTED_BY_REQUIRED,
                "requested_by is required when the recipe contains an approval gate");
    }
}
