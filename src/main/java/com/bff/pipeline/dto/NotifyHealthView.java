package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;

/**
 * 종단 알림의 건강 상태 조회 응답이다(ADR-022 §4, 구현 명세 §7). give-up 경보(배포 게이트)의 정규 소스인
 * DB 파생 술어를 HTTP로 노출하는 표면으로, 조직 alerting 스택이나 admin 대시보드가 주기 폴링한다 —
 * {@code give_up_count}가 0을 넘으면 자동 재시도가 멈춘 종단 알림이 있다는 뜻이라 담당자 개입이 필요하다.
 * 두 age의 기준 시각은 별도 필드로 구분한다 — {@code oldest_unnotified_at}은 비활성 채널 backlog까지 포함한
 * 총 미알림 기준 시각이고, {@code oldest_delivery_pending_at}은 due 행만 본 전달 정체 기준 시각이다.
 * 전달 정체 경보는 {@code channel_active}가 true일 때만 평가한다(비활성 채널은 정체가 아니라 gate).
 */
@Builder
public record NotifyHealthView(
        @JsonProperty("give_up_count") long giveUpCount,
        @JsonProperty("oldest_unnotified_at") Instant oldestUnnotifiedAt,
        @JsonProperty("oldest_delivery_pending_at") Instant oldestDeliveryPendingAt,
        @JsonProperty("channel_active") boolean channelActive) {
}
