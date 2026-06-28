package com.bff.pipeline.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.PipelineSettings;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The per-call boundary: a successful call returns its value, a slow call becomes
 * {@link ImCall.CallTimeoutException}, and an interrupted call becomes
 * {@link ImCall.CallInterruptedException} (a fail-fast signal, not a business outcome).
 */
class ImCallTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final ImCall imCall = new ImCall(pool, settingsWithPerCallTimeout(Duration.ofMillis(200)));

    @AfterEach
    void tearDown() {
        Thread.interrupted(); // clear any interrupt flag a test set, so it cannot leak
        pool.shutdownNow();
    }

    @Test
    void aSuccessfulCallReturnsItsValue() {
        assertThat(imCall.withTimeout(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void aCallThatExceedsThePerCallTimeoutThrowsCallTimeout() {
        assertThatThrownBy(() -> imCall.withTimeout(() -> {
            sleep(2_000);
            return "late";
        })).isInstanceOf(ImCall.CallTimeoutException.class);
    }

    @Test
    void anInterruptedCallThrowsCallInterruptedAndPreservesTheInterruptFlag() {
        Thread.currentThread().interrupt(); // the waiting thread is interrupted (e.g. shutdown)

        assertThatThrownBy(() -> imCall.withTimeout(() -> {
            sleep(2_000);
            return "x";
        })).isInstanceOf(ImCall.CallInterruptedException.class);

        assertThat(Thread.interrupted()).isTrue(); // re-set for the caller; also clears it here
    }

    private static PipelineSettings settingsWithPerCallTimeout(Duration perCall) {
        return new PipelineSettings(Duration.ofSeconds(15), perCall, Duration.ofMinutes(30),
                Duration.ofDays(7), Duration.ofMinutes(10), 3, 4);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
