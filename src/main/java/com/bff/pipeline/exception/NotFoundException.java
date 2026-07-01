package com.bff.pipeline.exception;

/**
 * 요청이 지목한 리소스가 존재하지 않는 경우를 나타낸다(예: 없는 pipelineId). REST 레이어
 * ({@code GlobalAdvice})에서 404 Not Found로 매핑된다. 입력 자체는 유효하되 대상이 없을 뿐이라
 * {@link BadRequestException}(400)과 구분한다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
