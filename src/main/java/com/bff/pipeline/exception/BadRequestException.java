package com.bff.pipeline.exception;

/**
 * 호출 계약이 잘못된 요청을 나타낸다(예: 필수 인자 누락). 도메인 규칙 위반이 아니라 입력 자체가 유효하지 않은
 * 경우이며, REST 레이어({@code GlobalAdvice})에서 400 Bad Request로 매핑된다.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
