package com.bff.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.NotifySettings;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link NotifySettings}가 잘못된 설정을 서버가 뜨는 시점에 바로 실패시키는지 검증한다.
 * 특히: 점유 시간이 Slack 호출 제한 시간보다 길어야 한다는 제약({@code leaseDuration > callTimeout}),
 * {@code backoffMax >= backoffBase}, 시간 값 양수, 시도 횟수 상한, jitter 범위.
 * 어느 것을 어기든 문제가 된 설정 키 이름이 에러 메시지에 들어가야 한다.
 * 채널이 환경변수 주입으로 바뀐 뒤(오너 결정 2026-07-09)의 조건부 필수도 검증한다 —
 * {@code enabled = true}면 webhook 주소(공백 불가)와 도입 시각({@code enabledAfter})이 필수이고,
 * 꺼진 배포는 둘 다 생략할 수 있다.
 */
class NotifySettingsTest {

    private static NotifySettings.NotifySettingsBuilder valid() {
        return NotifySettings.builder()
                .enabled(true)
                .pollInterval(Duration.ofSeconds(2)).maxIdleSleep(Duration.ofSeconds(10))
                .backoffBase(Duration.ofSeconds(5)).backoffMax(Duration.ofMinutes(10)).jitterRatio(0.2)
                .leaseDuration(Duration.ofMinutes(1)).callTimeout(Duration.ofSeconds(10))
                .maxAttempts(8).schedulerInitialDelay(Duration.ofSeconds(10))
                .slackWebhookUrl("https://hooks.slack.com/services/T0001/B0002/token")
                .enabledAfter(Instant.parse("2026-07-09T00:00:00Z"));
    }

    @Test
    void aValidConfigurationConstructs() {
        assertThatCode(() -> valid().build()).doesNotThrowAnyException();
    }

    @Test
    void anEnabledNotifierWithABlankWebhookFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().slackWebhookUrl(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.slack-webhook-url");
    }

    @Test
    void anEnabledNotifierWithoutAnAdoptionCutoffFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().enabledAfter(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.enabled-after");
    }

    @Test
    void aDisabledNotifierMayOmitBothTheWebhookAndTheAdoptionCutoff() {
        assertThatCode(() -> valid().enabled(false).slackWebhookUrl(null).enabledAfter(null).build())
                .doesNotThrowAnyException();
    }

    @Test
    void leaseNotExceedingCallTimeoutFailsFast() {
        assertThatThrownBy(() -> valid().leaseDuration(Duration.ofSeconds(10)).callTimeout(Duration.ofSeconds(10)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration")
                .hasMessageContaining("call-timeout");
    }

    @Test
    void backoffMaxBelowBackoffBaseFailsFast() {
        assertThatThrownBy(() -> valid().backoffBase(Duration.ofMinutes(20)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff-max")
                .hasMessageContaining("backoff-base");
    }

    @Test
    void aNonPositiveDurationFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().pollInterval(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.poll-interval");
    }

    @Test
    void aMissingDurationFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().callTimeout(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.call-timeout");
    }

    @Test
    void aMaxAttemptsBelowOneFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().maxAttempts(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.max-attempts");
    }

    @Test
    void aJitterRatioOutOfRangeFailsFast() {
        assertThatThrownBy(() -> valid().jitterRatio(1.5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitter-ratio");
    }
}
