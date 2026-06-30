package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.ExecutionSettings;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * PipelineScheduler 적응형 백오프 로직에 대한 순수 단위 테스트이다.
 * Spring 컨텍스트 없이 PipelineScheduler를 직접 생성하여 {@code nextDelay}와 {@code applyJitter}를
 * 검증한다. 실제 스레드 슬립은 없고 지연 시간 계산 로직만 테스트한다.
 */
class PipelineSchedulerBackoffTest {

    private static final Duration BACKOFF_BASE = Duration.ofMillis(200);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(5);
    private static final Duration MAX_IDLE_SLEEP = Duration.ofSeconds(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final double JITTER_RATIO = 0.2;

    private final PipelineScheduler scheduler = buildScheduler();

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    private static PipelineScheduler buildScheduler() {
        ExecutionSettings settings = ExecutionSettings.builder()
                .workerPerPod(1)
                .leaseDuration(Duration.ofMinutes(2))
                .apiCallTimeout(Duration.ofSeconds(30))
                .runningPipelineCap(100)
                .slotCap(20)
                .slotRetry(Duration.ofSeconds(1))
                .pollInterval(POLL_INTERVAL)
                .maxIdleSleep(MAX_IDLE_SLEEP)
                .backoffBase(BACKOFF_BASE)
                .backoffMax(BACKOFF_MAX)
                .jitterRatio(JITTER_RATIO)
                .build();
        // null worker, claimer, and pool are safe here: start() is never called, so no tasks are submitted.
        return new PipelineScheduler(null, null, null, settings, Duration.ofHours(1));
    }

    @Test
    void emptySweepsGrowTheDelayGeometricallyAndCapAtTheIdleSleepCeiling() {
        // idleBackoff starts at backoffBase (200ms).
        // Each empty sweep: idleBackoff = min(idleBackoff * 2, backoffMax); returned = min(idleBackoff, maxIdleSleep) ± jitter.
        // Progression (before jitter): 400ms → 800ms → 1600ms → 3000ms (capped) → 3000ms (stable).

        Duration delay1 = scheduler.nextDelay(false); // idleBackoff: 200 → 400ms; capped to 400ms
        Duration delay2 = scheduler.nextDelay(false); // idleBackoff: 400 → 800ms; capped to 800ms
        Duration delay3 = scheduler.nextDelay(false); // idleBackoff: 800 → 1600ms; capped to 1600ms
        Duration delay4 = scheduler.nextDelay(false); // idleBackoff: 1600 → 3200ms; capped to min(3200, 3000) = 3000ms
        Duration delay5 = scheduler.nextDelay(false); // idleBackoff: 3200 → 5000ms (backoffMax); capped to min(5000, 3000) = 3000ms

        // Verify each delay is within ±jitterRatio of its base value.
        assertWithinJitter(delay1, 400L);
        assertWithinJitter(delay2, 800L);
        assertWithinJitter(delay3, 1600L);
        assertWithinJitter(delay4, 3000L);
        assertWithinJitter(delay5, 3000L);

        // The returned delay must never exceed maxIdleSleep (ignoring jitter rounding up by at most 1ms).
        long maxAllowed = Math.round(MAX_IDLE_SLEEP.toMillis() * (1.0 + JITTER_RATIO));
        assertThat(delay4.toMillis()).isLessThanOrEqualTo(maxAllowed);
        assertThat(delay5.toMillis()).isLessThanOrEqualTo(maxAllowed);

        // Delays grow: delay1 < delay2 < delay3 (well below jitter overlap).
        assertThat(delay1.toMillis()).isLessThan(delay2.toMillis());
        assertThat(delay2.toMillis()).isLessThan(delay3.toMillis());
    }

    @Test
    void workFoundSweepResetsToPollInterval() {
        // Build up backoff with several empty sweeps.
        scheduler.nextDelay(false);
        scheduler.nextDelay(false);
        scheduler.nextDelay(false);

        // A work-found sweep returns exactly pollInterval (no jitter) and resets idleBackoff to backoffBase.
        Duration reset = scheduler.nextDelay(true);
        assertThat(reset).isEqualTo(POLL_INTERVAL);

        // After reset, the next empty sweep starts fresh from backoffBase → 400ms.
        Duration afterReset = scheduler.nextDelay(false);
        assertWithinJitter(afterReset, 400L);
    }

    @Test
    void jitterStaysWithinRatio() {
        Duration base = Duration.ofSeconds(2);
        long lowerBound = Math.round(base.toMillis() * (1.0 - JITTER_RATIO));
        long upperBound = Math.round(base.toMillis() * (1.0 + JITTER_RATIO));

        for (int iteration = 0; iteration < 200; iteration++) {
            Duration jittered = scheduler.applyJitter(base);
            assertThat(jittered.toMillis())
                    .as("applyJitter result must be within ±jitterRatio (iteration %d)", iteration)
                    .isBetween(lowerBound, upperBound);
        }
    }

    private static void assertWithinJitter(Duration actual, long baseMillis) {
        long lower = Math.round(baseMillis * (1.0 - JITTER_RATIO));
        long upper = Math.round(baseMillis * (1.0 + JITTER_RATIO));
        assertThat(actual.toMillis())
                .as("expected ~%dms (±%s%%)", baseMillis, (int) (JITTER_RATIO * 100))
                .isBetween(lower, upper);
    }
}
