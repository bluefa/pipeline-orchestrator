package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * period 쿼리 파라미터가 지원하지 않는 값인 경우다. 400 Bad Request + code
 * {@code ORCHESTRATION_INVALID_STATISTICS_PERIOD}로 매핑된다. 지원 토큰은 1h/1d/7d다.
 */
public class InvalidStatisticsPeriodException extends OrchestrationException {

    public InvalidStatisticsPeriodException(String token) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.INVALID_STATISTICS_PERIOD,
                "unsupported statistics period: " + token);
    }
}
