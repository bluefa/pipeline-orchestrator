package com.bff.pipeline.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.dto.ErrorResponse;
import com.bff.pipeline.exception.PipelineNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * GlobalAdvice가 BFF ErrorMessage 계약(timestamp/status/code/message/path)대로 오류 본문을 만드는지 검증한다.
 * status는 HttpStatus 문자열("404 NOT_FOUND"), timestamp는 주입된 Clock, path는 요청 URI다.
 */
class GlobalAdviceTest {

    private static final Instant NOW = Instant.parse("2026-07-02T00:00:00Z");

    private final GlobalAdvice advice = new GlobalAdvice(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void orchestrationExceptionMapsToBffStyleErrorBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pipelines/9999");

        ResponseEntity<ErrorResponse> response = advice.onOrchestration(new PipelineNotFoundException(9999L), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.timestamp()).isEqualTo(NOW);
        assertThat(body.status()).isEqualTo("404 NOT_FOUND");
        assertThat(body.code()).isEqualTo("ORCHESTRATION_PIPELINE_NOT_FOUND");
        assertThat(body.message()).contains("9999");
        assertThat(body.path()).isEqualTo("/api/v1/pipelines/9999");
    }

    @Test
    void invalidRequestParameterMapsToBadRequestErrorBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pipelines/statistics");

        ErrorResponse body = advice.onInvalidRequest(
                new MissingServletRequestParameterException("period", "String"), request);

        assertThat(body.status()).isEqualTo("400 BAD_REQUEST");
        assertThat(body.code()).isEqualTo("ORCHESTRATION_INVALID_PARAMETER");
        assertThat(body.path()).isEqualTo("/api/v1/pipelines/statistics");
        assertThat(body.timestamp()).isEqualTo(NOW);
    }
}
