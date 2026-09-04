package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 확인 요청 메시지가 저장 가능한 길이를 넘겼다. 사람이 승인자에게 쓴 말을 말없이 자르면 의도가 훼손되므로
 * 자르지 않고 400으로 되돌려 보낸다(승인 게이트 ADR §결정 4).
 */
public class RequestNoteTooLongException extends OrchestrationException {

    public RequestNoteTooLongException(int length, int maxLength) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.REQUEST_NOTE_TOO_LONG,
                "request_note is " + length + " chars, exceeds max " + maxLength);
    }
}
