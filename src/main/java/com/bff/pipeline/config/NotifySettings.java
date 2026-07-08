package com.bff.pipeline.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code pipeline.notify.*} 키에서 바인딩되는 ADR-022 종단 상태 알림(notify) 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline.notify")}). 실행 설정({@link ExecutionSettings})과
 * 자원·회계를 공유하지 않는 별도 loop의 케이던스·backoff·lease·give-up 임계를 이 record가 소유한다.
 *
 * compact constructor가 fail-fast로 검증한다 — 모든 Duration은 양수, {@code maxAttempts}는 1 이상,
 * {@code jitterRatio}는 0~1 범위, {@code backoffMax >= backoffBase}, 그리고 ADR-021 Decision 5와 같은 이유의
 * 하드 제약 {@code leaseDuration > callTimeout}을 강제한다(어기면 문제 키 이름과 함께 시작에 실패한다).
 * lease가 호출 타임아웃보다 짧으면 정상 운영 중에도 write-back이 만료된 lease로 no-op되는 병리가 생긴다.
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
        Duration schedulerInitialDelay) {

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
