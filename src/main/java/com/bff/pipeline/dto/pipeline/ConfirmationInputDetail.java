package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskConfirmationInput;
import com.bff.pipeline.utils.HttpResponses;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import lombok.Builder;

/** Task가 등록에 사용하는 추천 원문과 성공 GET의 증적이다. 아직 조회 전인 정상 행은 본문·캡처 시각이 null이다. */
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConfirmationInputDetail(Long inputId, String recommendationBody, String bodyDigest,
        String approvalVersion, String targetGeneration, boolean applyNlbSecurityGroup,
        Integer sourceAttemptNumber, HttpResponseDetail.Metadata response, Instant capturedAt) {
    public static ConfirmationInputDetail from(TaskConfirmationInput input) {
        return builder().inputId(input.getId()).recommendationBody(input.getRecommendationBody())
                .bodyDigest(input.getBodyDigest()).approvalVersion(input.getApprovalVersion())
                .targetGeneration(input.getTargetGeneration()).applyNlbSecurityGroup(input.isApplyNlbSecurityGroup())
                .sourceAttemptNumber(input.getSourceAttemptNumber()).capturedAt(input.getCapturedAt())
                .response(HttpResponseDetail.Metadata.builder().operation(HttpResponses.RECOMMENDATION_GET)
                        .statusCode(input.getHttpStatusCode()).contentType(input.getResponseContentType())
                        .receivedAt(input.getResponseReceivedAt()).truncated(false).confirmationInputId(input.getId()).build()).build();
    }
}
