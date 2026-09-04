package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.PipelineType;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카탈로그 파이프라인 실행(P10) 요청 본문이다. type은 INSTALL 또는 DELETE.
 *
 * {@code requested_by}/{@code request_note}는 요청 맥락이다 — 누가 이 실행을 요청했고 무슨 말을 남겼는가.
 * 요청자는 검증된 계정에서 BFF가 채워 보내며 오케스트레이터는 기록만 한다. 둘 다 선택값이다.
 *
 * 와이어 필드는 snake_case 계약을 따른다.
 */
public record CreatePipelineRequest(
        @JsonProperty("type") PipelineType type,
        @JsonProperty("requested_by") String requestedBy,
        @JsonProperty("request_note") String requestNote) {
}
