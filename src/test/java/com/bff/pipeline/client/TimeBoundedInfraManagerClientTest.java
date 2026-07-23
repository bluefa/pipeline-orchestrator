package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.exception.CallInterruptedException;
import com.bff.pipeline.exception.CallFailedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link TimeBoundedInfraManagerClient} per-call timeout 데코레이터 단위 테스트. 실제 스레드풀 + 스크립터블
 * delegate로 타임아웃/예외 전파/인터럽트 동작을 검증한다.
 */
class TimeBoundedInfraManagerClientTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TimeBoundedInfraManagerClient decorator(InfraManagerClient delegate) {
        ExecutionSettings settings = ExecutionSettings.builder()
                .workerPerPod(2).leaseDuration(Duration.ofSeconds(1)).apiCallTimeout(Duration.ofMillis(200))
                .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                .schedulerInitialDelay(Duration.ofSeconds(5))
                .build();
        return new TimeBoundedInfraManagerClient(delegate, pool, settings, meterRegistry);
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void aHappyCallReturnsTheDelegateValue() {
        assertThat(decorator(delegate(() -> "[\"job-1\"]")).runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo("[\"job-1\"]");
    }

    @Test
    void aSlowCallBecomesCallTimeout() {
        InfraManagerClient slow = delegate(() -> {
            sleep(2000);
            return "late";
        });
        assertThatThrownBy(() -> decorator(slow).runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void aCallFailedFromTheDelegatePassesThrough() {
        InfraManagerClient failing = delegate(() -> { throw new CallFailedException("503"); });
        assertThatThrownBy(() -> decorator(failing).runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class)
                .hasMessageContaining("503");
    }

    @Test
    void aFatalErrorFromTheDelegatePropagatesAsError() {
        InfraManagerClient fatal = delegate(() -> { throw new OutOfMemoryError("boom"); });
        assertThatThrownBy(() -> decorator(fatal).runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void anInterruptedCallBecomesCallInterruptedAndRestoresTheFlag() {
        InfraManagerClient delegate = delegate(() -> "value");
        Thread.currentThread().interrupt();   // get() will observe the interrupt promptly
        assertThatThrownBy(() -> decorator(delegate).runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallInterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();   // restored (and cleared for the next test)
    }

    @Test
    void everyCallOutcomeIsTimedUnderItsOwnTag() {
        decorator(delegate(() -> "ok")).runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY);
        assertThatThrownBy(() -> decorator(delegate(() -> { throw new CallFailedException("503"); }))
                .runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY)).isInstanceOf(CallFailedException.class);
        assertThatThrownBy(() -> decorator(delegate(() -> { sleep(2000); return "late"; }))
                .runTerraform("t", TaskOperation.AWS_SERVICE_TF_APPLY)).isInstanceOf(CallTimeoutException.class);

        assertThat(callCount(TimeBoundedInfraManagerClient.OUTCOME_SUCCESS)).isEqualTo(1);
        assertThat(callCount(TimeBoundedInfraManagerClient.OUTCOME_FAILURE)).isEqualTo(1);
        assertThat(callCount(TimeBoundedInfraManagerClient.OUTCOME_TIMEOUT)).isEqualTo(1);
    }

    private long callCount(String outcome) {
        return meterRegistry.timer(TimeBoundedInfraManagerClient.INFRA_MANAGER_CALL,
                TimeBoundedInfraManagerClient.OUTCOME_TAG, outcome).count();
    }

    private static InfraManagerClient delegate(Supplier<String> dispatch) {
        return new InfraManagerClient() {
            @Override public String runTerraform(String target, TaskOperation operation) { return dispatch.get(); }
            @Override public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) { return TerraformPoll.running("RUNNING"); }
            @Override public String terraformJobResult(String jobId, TaskOperation operation) { return "terraform: ok"; }
            @Override public ConditionPoll checkCondition(String target, TaskOperation operation) { return new ConditionPoll(false, "{}"); }
            @Override public CloudProvider cloudProvider(String target) { return CloudProvider.AWS; }
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
