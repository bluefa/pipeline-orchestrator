package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.repository.TaskAttemptMetadata;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import lombok.Builder;

/** 소유 관계를 검증한 attempt의 현재 HTTP 본문과 metadata다. 목록은 내부 Metadata 값만 노출한다. */
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HttpResponseDetail(Metadata metadata, String body) {
    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Metadata(String operation, Integer statusCode, String contentType, Instant receivedAt,
            Boolean truncated, Long confirmationInputId) {
        public static Metadata from(TaskAttemptMetadata attempt) {
            return builder().operation(attempt.getHttpOperation()).statusCode(attempt.getHttpStatusCode())
                    .contentType(attempt.getResponseContentType()).receivedAt(attempt.getResponseReceivedAt())
                    .truncated(attempt.getResponseTruncated()).confirmationInputId(attempt.getConfirmationInputId()).build();
        }
    }

    public static HttpResponseDetail from(TaskAttempt attempt) {
        return builder().body(attempt.getHttpResponse()).metadata(Metadata.builder()
                .operation(attempt.getHttpOperation()).statusCode(attempt.getHttpStatusCode())
                .contentType(attempt.getResponseContentType()).receivedAt(attempt.getResponseReceivedAt())
                .truncated(attempt.getResponseTruncated()).confirmationInputId(attempt.getConfirmationInputId()).build()).build();
    }
}
