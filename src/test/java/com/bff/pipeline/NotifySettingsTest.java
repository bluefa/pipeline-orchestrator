package com.bff.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.NotifySettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link NotifySettings} 부팅-시 fail-fast 검증(ADR-022 구현 명세 §3). 특히 {@code leaseDuration > callTimeout}
 * 하드 제약과 {@code backoffMax >= backoffBase}, 양수 Duration/시도 상한/jitter 경계를 어기면 문제 키 이름과
 * 함께 즉시 실패해야 한다.
 */
class NotifySettingsTest {

    private static NotifySettings.NotifySettingsBuilder valid() {
        return NotifySettings.builder()
                .enabled(true)
                .pollInterval(Duration.ofSeconds(2)).maxIdleSleep(Duration.ofSeconds(10))
                .backoffBase(Duration.ofSeconds(5)).backoffMax(Duration.ofMinutes(10)).jitterRatio(0.2)
                .leaseDuration(Duration.ofMinutes(1)).callTimeout(Duration.ofSeconds(10))
                .maxAttempts(8).schedulerInitialDelay(Duration.ofSeconds(10));
    }

    @Test
    void aValidConfigurationConstructs() {
        assertThatCode(() -> valid().build()).doesNotThrowAnyException();
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
