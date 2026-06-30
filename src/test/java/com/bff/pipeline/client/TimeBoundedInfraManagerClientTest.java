package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TimeBoundedInfraManagerClient} per-call timeout 데코레이터 단위 테스트. 실제 스레드풀 + 스크립터블
 * delegate로 타임아웃/예외 전파/인터럽트 동작을 검증한다.
 */
class TimeBoundedInfraManagerClientTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private TimeBoundedInfraManagerClient decorator(InfraManagerClient delegate) {
        ExecutionSettings settings = ExecutionSettings.builder()
                .workerPerPod(2).leaseDuration(Duration.ofSeconds(1)).apiCallTimeout(Duration.ofMillis(200))
                .runningPipelineCap(100).slotCap(100).slotRetry(Duration.ofSeconds(1))
                .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                .build();
        return new TimeBoundedInfraManagerClient(delegate, pool, settings);
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void aHappyCallReturnsTheDelegateValue() {
        assertThat(decorator(delegate(() -> "[\"job-1\"]")).runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isEqualTo("[\"job-1\"]");
    }

    @Test
    void aSlowCallBecomesCallTimeout() {
        InfraManagerClient slow = delegate(() -> {
            sleep(2000);
            return "late";
        });
        assertThatThrownBy(() -> decorator(slow).runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallTimeoutException.class);
    }

    @Test
    void aCallFailedFromTheDelegatePassesThrough() {
        InfraManagerClient failing = delegate(() -> { throw new InfraManagerClient.CallFailedException("503"); });
        assertThatThrownBy(() -> decorator(failing).runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallFailedException.class)
                .hasMessageContaining("503");
    }

    @Test
    void aFatalErrorFromTheDelegatePropagatesAsError() {
        InfraManagerClient fatal = delegate(() -> { throw new OutOfMemoryError("boom"); });
        assertThatThrownBy(() -> decorator(fatal).runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void anInterruptedCallBecomesCallInterruptedAndRestoresTheFlag() {
        InfraManagerClient delegate = delegate(() -> "value");
        Thread.currentThread().interrupt();   // get() will observe the interrupt promptly
        assertThatThrownBy(() -> decorator(delegate).runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallInterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();   // restored (and cleared for the next test)
    }

    private static InfraManagerClient delegate(java.util.function.Supplier<String> dispatch) {
        return new InfraManagerClient() {
            @Override public String runTerraform(String target, TaskOperation operation) { return dispatch.get(); }
            @Override public TerraformPoll terraformJobStatus(String jobId) { return TerraformPoll.running(); }
            @Override public boolean checkCondition(String target, TaskOperation operation) { return false; }
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
