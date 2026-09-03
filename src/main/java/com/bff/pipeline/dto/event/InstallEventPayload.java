package com.bff.pipeline.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 자동 설치 이벤트의 본문이다. 대상이 4단계로 넘어갈 때 발행 측이 Pub/Sub에 싣는 JSON이며, 생성 API 본문과 같은
 * 모양에 cloud provider를 더한 것이다:
 *
 * {@code {"target_source_id": "...", "cloud_provider": "AZURE", "type": "INSTALL"}}
 *
 * {@code type}은 지금 INSTALL만 받는다. {@code cloud_provider}는 자동 설치를 허용할 provider를 고르는 열쇠이자,
 * 카탈로그가 아는 provider와 대조해 발행 측 오류를 잡는 값이다. 모르는 필드는 무시한다(발행 측이 필드를 먼저 더해도
 * 수신이 깨지지 않게).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallEventPayload(
        @JsonProperty("target_source_id") String targetSourceId,
        @JsonProperty("cloud_provider") String cloudProvider,
        @JsonProperty("type") String type) {
}
