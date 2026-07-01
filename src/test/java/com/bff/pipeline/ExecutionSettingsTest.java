package com.bff.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.ExecutionSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link ExecutionSettings} 부팅-시 fail-fast 검증(ADR-021 Decision 5의 코드 강제 부분집합). 특히
 * {@code leaseDuration > apiCallTimeout} 하드 제약과 양수/캡/jitter 경계를 어기면 키 이름과 함께 즉시 실패해야 한다.
 */
class ExecutionSettingsTest {

    private static ExecutionSettings.ExecutionSettingsBuilder valid() {
        return ExecutionSettings.builder()
                .workerPerPod(4).leaseDuration(Duration.ofMinutes(2)).apiCallTimeout(Duration.ofSeconds(30))
                .runningPipelineCap(100).terraformSlotCap(20).terraformSlotRetry(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(5))
                .backoffBase(Duration.ofMillis(200)).backoffMax(Duration.ofSeconds(5)).jitterRatio(0.2);
    }

    @Test
    void aValidConfigurationConstructs() {
        assertThatCode(() -> valid().build()).doesNotThrowAnyException();
    }

    @Test
    void leaseNotExceedingApiCallTimeoutFailsFast() {
        assertThatThrownBy(() -> valid().leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(30)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration")
                .hasMessageContaining("api-call-timeout");
    }

    @Test
    void aNonPositiveWorkerCountFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().workerPerPod(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.execution.worker-per-pod");
    }

    @Test
    void aNonPositiveDurationFailsFastWithItsKey() {
        assertThatThrownBy(() -> valid().leaseDuration(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.execution.lease-duration");
    }

    @Test
    void aJitterRatioOutOfRangeFailsFast() {
        assertThatThrownBy(() -> valid().jitterRatio(1.5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitter-ratio");
    }
}
