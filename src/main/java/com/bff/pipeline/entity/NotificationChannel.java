package com.bff.pipeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADR-022 종단 알림의 Slack 채널 설정 행이다. V1은 단일 논리 sink이므로 이 테이블은 사실상 1행이다
 * ({@code id = 1} 고정, {@link #SINGLETON_ID}) — admin이 이 1행을 upsert로 수정한다. 알림 전달 상태
 * (notified_at 등)는 {@link Pipeline}이 갖고, 이 행은 어디로 보낼지(webhook)와 보낼지 말지(enabled)만 갖는다.
 * 채널 미설정/비활성이면 notify 스케줄러는 claim 자체를 하지 않고 idle한다(backlog는 보존돼 소급 발화).
 */
@Entity
@Table(name = "notification_channel")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NotificationChannel {

    /** 단일 sink이므로 고정 PK. upsert는 항상 이 id를 쓴다. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Slack Incoming Webhook URL. secret — 조회 응답에서는 마스킹한다(원문은 절대 반환하지 않는다). */
    @Column(name = "slack_webhook_url", length = 512)
    private String slackWebhookUrl;

    /** admin 표시용 별칭(예: "#infra-alerts"). 전송 라우팅엔 쓰지 않는다 — webhook이 채널을 결정한다. */
    @Column(name = "channel_label", length = 128)
    private String channelLabel;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
