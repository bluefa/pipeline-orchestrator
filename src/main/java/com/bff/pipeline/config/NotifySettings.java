package com.bff.pipeline.config;

import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code pipeline.notify.*} 키에서 바인딩되는 ADR-022 종단 상태 알림(notify) 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline.notify")}). 실행 설정({@link ExecutionSettings})과
 * 자원·회계를 공유하지 않는 별도 loop의 케이던스·backoff·lease·give-up 임계를 이 record가 소유한다.
 *
 * Slack 전달 채널도 이 설정이 소유한다(오너 결정 2026-07-09 — admin 채널 관리 대신 환경변수 주입).
 * {@code slackWebhookUrl}은 env로 주입되는 secret이다 — 어떤 로그·응답에도 원문을 찍지 않는다.
 * {@code enabledAfter}는 알림 도입 시점 컷오프다(ADR-022 §5의 대안인 활성 컷오프 술어) — claim 술어가
 * {@code last_activity_at >= enabledAfter}를 요구하므로 그 이전에 종단된 행은 알림 범위 밖이 되어 레거시
 * 종단 행의 소급 발화 폭주를 막는다. backfill 마이그레이션이 없는 방식이라 이 값은 상시 유지해야 한다
 * (지우면 레거시 전부가 다시 알림 대상이 된다).
 *
 * compact constructor가 fail-fast로 검증한다 — 모든 Duration은 양수, {@code maxAttempts}는 1 이상,
 * {@code jitterRatio}는 0~1 범위, {@code backoffMax >= backoffBase}, 그리고 ADR-021 Decision 5와 같은 이유의
 * 하드 제약 {@code leaseDuration > callTimeout}을 강제한다(어기면 문제 키 이름과 함께 시작에 실패한다).
 * lease가 호출 타임아웃보다 짧으면 정상 운영 중에도 write-back이 만료된 lease로 no-op되는 병리가 생긴다.
 * {@code enabled = true}일 때만 {@code slackWebhookUrl}(non-blank)과 {@code enabledAfter}(non-null)가
 * 필수다 — notifier를 켜는 배포는 전달 채널과 컷오프를 함께 제공해야 하고, 꺼진 배포는 둘 다 생략할 수 있다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline.notify")
public record NotifySettings(
        boolean enabled,
        Duration pollInterval,
        Duration maxIdleSleep,
        Duration backoffBase,
        Duration backoffMax,
        double jitterRatio,
        Duration leaseDuration,
        Duration callTimeout,
        int maxAttempts,
        Duration schedulerInitialDelay,
        String slackWebhookUrl,
        Instant enabledAfter) {

    public NotifySettings {
        requirePositive(pollInterval, "pipeline.notify.poll-interval");
        requirePositive(maxIdleSleep, "pipeline.notify.max-idle-sleep");
        requirePositive(backoffBase, "pipeline.notify.backoff-base");
        requirePositive(backoffMax, "pipeline.notify.backoff-max");
        requirePositive(leaseDuration, "pipeline.notify.lease-duration");
        requirePositive(callTimeout, "pipeline.notify.call-timeout");
        requirePositive(schedulerInitialDelay, "pipeline.notify.scheduler-initial-delay");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("pipeline.notify.max-attempts must be >= 1, was " + maxAttempts);
        }
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException(
                    "pipeline.notify.jitter-ratio must be within [0.0, 1.0], was " + jitterRatio);
        }
        // backoffBase는 delivery-실패 backoff와 idle-sleep seed 두 곳에 쓰인다.
        // backoffBase > backoffMax면 지수 backoff가 즉시 clamp돼 무의미해지므로 막는다.
        if (backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("pipeline.notify.backoff-max (" + backoffMax
                    + ") must be >= pipeline.notify.backoff-base (" + backoffBase + ")");
        }
        if (leaseDuration.compareTo(callTimeout) <= 0) {
            throw new IllegalArgumentException("pipeline.notify.lease-duration (" + leaseDuration
                    + ") must exceed pipeline.notify.call-timeout (" + callTimeout
                    + ") per ADR-021 Decision 5");
        }
        if (enabled) {
            if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
                throw new IllegalArgumentException("pipeline.notify.slack-webhook-url must be set when "
                        + "pipeline.notify.enabled is true (inject the secret via environment variable)");
            }
            if (enabledAfter == null) {
                throw new IllegalArgumentException("pipeline.notify.enabled-after must be set when "
                        + "pipeline.notify.enabled is true (adoption cutoff, ADR-022 §5 — terminal rows "
                        + "before it stay out of notification scope)");
            }
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null) {
            throw new IllegalArgumentException(property + " must be set");
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + value);
        }
    }
}
