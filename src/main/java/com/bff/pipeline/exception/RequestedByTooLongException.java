package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청자 값이 저장 가능한 길이를 넘겼다. 잘라서 저장하면 감사 기록이 다른 사람을 가리킬 수 있으므로
 * 자르지 않고 400으로 되돌려 보낸다(승인 게이트 ADR §결정 4).
 */
public class RequestedByTooLongException extends OrchestrationException {

    public RequestedByTooLongException(int length, int maxLength) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.REQUESTED_BY_TOO_LONG,
                "requested_by is " + length + " chars, exceeds max " + maxLength);
    }
}
