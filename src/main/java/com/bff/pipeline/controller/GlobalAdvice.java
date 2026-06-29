package com.bff.pipeline.controller;

import com.bff.pipeline.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates uncaught exceptions from the REST layer into a small JSON error body. There are no
 * controllers yet (this module is the ADR-016 domain model); this is the seam the REST layer added
 * later plugs into. The catch-all handler never swallows the cause — it logs the exception (the trace)
 * while returning a generic body.
 */
@RestControllerAdvice
public class GlobalAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalAdvice.class);

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
