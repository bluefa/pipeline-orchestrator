package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;

/**
 * 알림 채널 설정 조회/upsert 응답이다(ADR-022 §6.1). webhook은 secret이라 원문 대신
 * {@code webhook_configured}(설정 여부)와 {@code webhook_masked}(뒤 4자만 보이는 마스킹)만 내려간다 —
 * 어떤 응답에도 webhook 원문은 실리지 않는다.
 */
@Builder
public record ChannelView(
        @JsonProperty("channel_label") String channelLabel,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("webhook_configured") boolean webhookConfigured,
        @JsonProperty("webhook_masked") String webhookMasked,
        @JsonProperty("updated_at") Instant updatedAt) {
}
