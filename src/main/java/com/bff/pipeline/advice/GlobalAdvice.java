package com.bff.pipeline.advice;

import com.bff.pipeline.dto.ErrorResponse;
import com.bff.pipeline.exception.OrchestrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 레이어에서 처리되지 않은 예외를 작은 JSON 오류 본문으로 바꾸는 {@code @RestControllerAdvice}다.
 * 지금 이 모듈에는 컨트롤러가 없지만(이 모듈은 ADR-016 도메인 모델이다) 나중에 REST 레이어가 붙을 때
 * 이어질 이음새(seam)로 미리 둔다.
 *
 * <p>{@link OrchestrationException}은 자신이 매핑될 HTTP status와 안정적인 {@code ORCHESTRATION_*} code를
 * 직접 들고 오므로 한 핸들러가 그대로 옮긴다(케이스마다 전용 서브타입 — 제네릭 400 버킷을 두지 않는다).
 * catch-all은 예상 밖 예외의 원인을 스택 트레이스까지 로깅한 뒤 500으로 내려준다(원인을 삼키지 않는다).
 */
@Slf4j
@RestControllerAdvice
public class GlobalAdvice {

    @ExceptionHandler(OrchestrationException.class)
    public ResponseEntity<ErrorResponse> onOrchestration(OrchestrationException exception) {
        return ResponseEntity.status(exception.status()).body(body(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse onUnexpected(Exception exception) {
        log.error("Unhandled exception in the REST layer", exception);
        return body("ORCHESTRATION_INTERNAL_ERROR", "unexpected error");
    }

    private static ErrorResponse body(String code, String message) {
        return ErrorResponse.builder().code(code).message(message).build();
    }
}
