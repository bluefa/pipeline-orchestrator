package com.bff.pipeline.controller;

import com.bff.pipeline.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 레이어에서 발생한 미처리 예외를 소형 JSON 오류 본문으로 변환하는 {@code @RestControllerAdvice}이다.
 * 현재 이 모듈에는 컨트롤러가 없으며(이 모듈은 ADR-016 도메인 모델이다), 추후 REST 레이어가 추가될 때
 * 연결될 이음새(seam) 역할을 한다. catch-all 핸들러는 원인을 절대 무시하지 않으며,
 * 예외(스택 트레이스 포함)를 로깅한 후 제네릭 응답 본문을 반환한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse onBadRequest(IllegalArgumentException exception) {
        return new ErrorResponse("BAD_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse onUnexpected(Exception exception) {
        log.error("Unhandled exception in the REST layer", exception);
        return new ErrorResponse("INTERNAL_ERROR", "unexpected error");
    }
}
