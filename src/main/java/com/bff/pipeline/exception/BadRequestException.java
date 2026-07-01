package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 호출 계약이 잘못된 요청을 나타낸다(예: 필수 인자 누락). 도메인 규칙 위반이 아니라 입력 자체가 유효하지 않은
 * 경우이며, 400 Bad Request + 호출자가 지정한 {@code ORCHESTRATION_*} code로 매핑된다.
 */
public class BadRequestException extends OrchestrationException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
