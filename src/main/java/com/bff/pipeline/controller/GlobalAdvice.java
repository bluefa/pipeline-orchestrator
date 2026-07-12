package com.bff.pipeline.controller;

import com.bff.pipeline.dto.ErrorResponse;
import com.bff.pipeline.exception.OrchestrationErrorCode;
import com.bff.pipeline.exception.OrchestrationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * REST 레이어에서 처리되지 않은 예외를 오류 JSON 본문으로 바꾸는 {@code @RestControllerAdvice}다. 본문은 BFF
 * swagger의 ErrorMessage 계약과 동형이다 — timestamp/status/code/message/path. timestamp는 주입된 {@link Clock},
 * path는 요청 URI, status는 HttpStatus 문자열(예 "404 NOT_FOUND")로 채운다.
 *
 * <p>{@link OrchestrationException}은 자신이 매핑될 HTTP status와 안정적인 {@code ORCHESTRATION_*} code를
 * 직접 들고 오므로 한 핸들러가 그대로 옮긴다(케이스마다 전용 서브타입 — 제네릭 400 버킷을 두지 않는다). 잘못된/누락
 * 요청 파라미터는 400 INVALID_PARAMETER로 모은다. catch-all은 예상 밖 예외의 원인을 스택 트레이스까지 로깅한 뒤
 * 500으로 내려준다(원인을 삼키지 않는다).
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalAdvice {

    private final Clock clock;

    @ExceptionHandler(OrchestrationException.class)
    public ResponseEntity<ErrorResponse> onOrchestration(OrchestrationException exception, HttpServletRequest request) {
        if (exception.status().is5xxServerError()) {
            log.error("Orchestration server error [{}]", exception.code(), exception);
        }
        return ResponseEntity.status(exception.status())
                .body(body(exception.status(), exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse onInvalidRequest(Exception exception, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.INVALID_PARAMETER.code(),
                "invalid or missing request parameter", request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse onUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception in the REST layer", exception);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, OrchestrationErrorCode.INTERNAL_ERROR.code(),
                "unexpected error", request);
    }

    private ErrorResponse body(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ErrorResponse.builder()
                .timestamp(clock.instant())
                .status(status.toString())   // "404 NOT_FOUND" 형태 — BFF ErrorMessage.status와 동일
                .code(code)
                .message(message)
                .path(request.getRequestURI())
                .build();
    }
}
