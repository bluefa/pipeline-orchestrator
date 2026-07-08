package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 알림 채널 upsert 요청 본문이다(PUT /api/v1/admin/notification-channel, ADR-022 §6.1). {@code enabled}는
 * 생략(null)과 false를 구분하기 위해 원시형이 아닌 {@code Boolean}이다 — null이면 400으로 거절한다.
 * {@code slack_webhook_url}은 선택이다 — null/blank면 저장된 값을 유지하고, 값이 오면 검증 후 교체한다.
 */
public record ChannelUpsert(
        @JsonProperty("channel_label") String channelLabel,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("slack_webhook_url") String slackWebhookUrl) {
}
