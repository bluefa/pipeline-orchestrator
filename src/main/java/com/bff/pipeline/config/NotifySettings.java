package com.bff.pipeline.config;

import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 종단 알림 기능의 설정 모음이다. application.yml의 {@code pipeline.notify.*} 키에서 읽어온다.
 *
 * 각 설정의 의미:
 * - {@code enabled}: 알림 기능 스위치. 끄면(기본값) 알림 스레드가 아예 돌지 않는다.
 * - {@code slackWebhookUrl}: 알림을 보낼 Slack Incoming Webhook 주소. 환경변수
 *   {@code PIPELINE_NOTIFY_SLACK_WEBHOOK_URL}로 주입하는 비밀값이라 로그나 API 응답에 원문을 남기지 않는다.
 * - {@code enabledAfter}: 이 시각 이후에 끝난 파이프라인만 알린다는 기준 시각. 알림 기능을 처음 켜는 순간
 *   과거에 끝난 파이프라인 전부가 한꺼번에 Slack으로 쏟아지는 것을 막는다. 이 값을 지우거나 과거로 옮기면
 *   옛 파이프라인들이 다시 알림 대상이 되므로, 한 번 정했으면 계속 유지해야 한다.
 * - {@code pollInterval}, {@code maxIdleSleep}: 알림 스레드가 얼마나 자주 도는지. 보낼 게 있으면
 *   pollInterval 간격으로 돌고, 없으면 maxIdleSleep까지 점점 느리게 돈다.
 * - {@code backoffBase}, {@code backoffMax}, {@code jitterRatio}: 전송이 실패했을 때 다음 재시도까지
 *   기다리는 시간. 실패가 반복될수록 backoffBase에서 backoffMax까지 두 배씩 늘어난다.
 * - {@code leaseDuration}: 한 서버가 한 건의 알림 전송을 점유하는 시간. 점유한 서버가 죽어도 이 시간이
 *   지나면 다른 서버가 이어받는다.
 * - {@code callTimeout}: Slack HTTP 호출 제한 시간.
 * - {@code maxAttempts}: 이 횟수만큼 전송에 실패하면 자동 재시도를 멈추고 사람 개입을 기다린다.
 *
 * 잘못된 설정은 서버가 뜨는 시점에 바로 실패시키고, 어느 키가 문제인지 메시지에 담는다. 시간 값은 모두
 * 양수여야 하고, maxAttempts는 1 이상, jitterRatio는 0~1, backoffMax는 backoffBase 이상이어야 한다.
 * leaseDuration은 callTimeout보다 길어야 한다 — 짧으면 Slack 응답을 기다리는 사이에 점유가 먼저 풀려서,
 * 정상 동작인데도 전송 결과가 기록되지 못하는 문제가 생긴다. 알림을 켜는(enabled=true) 배포는
 * slackWebhookUrl과 enabledAfter를 반드시 함께 줘야 하고, 꺼진 배포는 둘 다 생략할 수 있다.
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
