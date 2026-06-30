package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TimeBoundedInfraManagerClient} 데코레이터에 대한 순수 단위 테스트이다.
 * Spring 컨텍스트 없이 {@link ExecutorService}와 {@link ExecutionSettings}를 직접 주입하여
 * 타임아웃({@code CallTimeoutException}), 인터럽트({@code CallInterruptedException}), 치명적 오류(Error)
 * 전파, {@code CallFailedException} 폐쇄 어휘 보존, 정상 반환 경로를 각각 독립적으로 검증한다.
 * Mockito 대신 동작을 스크립팅 가능한 내부 테스트 더블을 사용한다.
 */
class TimeBoundedInfraManagerClientTest {

    /** apiCallTimeout=80ms, leaseDuration=500ms(>apiCallTimeout 하드 제약 준수). */
    private static final ExecutionSettings SETTINGS = ExecutionSettings.builder()
            .workerPerPod(1)
            .leaseDuration(Duration.ofMillis(500))
            .apiCallTimeout(Duration.ofMillis(80))
            .runningPipelineCap(10)
            .slotCap(10)
            .slotRetry(Duration.ofSeconds(1))
            .pollInterval(Duration.ofSeconds(1))
            .maxIdleSleep(Duration.ofSeconds(1))
            .backoffBase(Duration.ofMillis(100))
            .backoffMax(Duration.ofSeconds(1))
            .jitterRatio(0.0)
            .build();

    private final ExecutorService imCallPool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        imCallPool.shutdownNow();
        Thread.interrupted(); // 잔여 인터럽트 플래그 정리
    }

    // ── 테스트 더블 ───────────────────────────────────────────────────────────

    /**
     * 스크립터블 {@link InfraManagerClient} 테스트 더블이다.
     * {@code runTerraform}에만 동작을 주입하여 사용한다 — 다른 메서드는 안전한 기본값을 반환한다.
     */
    static final class ScriptableDelegate implements InfraManagerClient {

        private volatile Supplier<String> terraformBehavior = () -> "job-1";

        void onTerraform(Supplier<String> behavior) {
            this.terraformBehavior = behavior;
        }

        @Override
        public String runTerraform(String target, TaskOperation operation) {
            return terraformBehavior.get();
        }

        @Override
        public TerraformPoll terraformJobStatus(String jobId) {
            return TerraformPoll.running();
        }

        @Override
        public boolean checkCondition(String target, TaskOperation operation) {
            return false;
        }
    }

    private TimeBoundedInfraManagerClient newDecorator(InfraManagerClient delegate) {
        return new TimeBoundedInfraManagerClient(delegate, imCallPool, SETTINGS);
    }

    // ── 테스트 ───────────────────────────────────────────────────────────────

    /**
     * 대리자가 apiCallTimeout(80ms)보다 훨씬 오래 걸릴 때 {@code CallTimeoutException}이 발생하고,
     * 전체 슬립 시간(500ms)이 아닌 타임아웃 근처에서 빠르게 반환된다.
     */
    @Test
    void aSlowCallBecomesCallTimeout() {
        ScriptableDelegate delegate = new ScriptableDelegate();
        delegate.onTerraform(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "too-late";
        });
        TimeBoundedInfraManagerClient decorator = newDecorator(delegate);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> decorator.runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallTimeoutException.class);
        long elapsed = System.currentTimeMillis() - start;

        // 타임아웃(80ms) 근처에서 반환되어야 하며, 500ms 슬립 전체를 기다리지 않는다.
        assertThat(elapsed).isLessThan(400);
    }

    /**
     * 호출 전에 현재 스레드의 인터럽트 플래그가 이미 설정된 경우 {@code CallInterruptedException}이 발생하고,
     * 데코레이터가 인터럽트 플래그를 복원한다({@code Thread.interrupted()}로 확인 후 소비).
     */
    @Test
    void anInterruptedCallBecomesCallInterruptedAndRestoresTheFlag() {
        ScriptableDelegate delegate = new ScriptableDelegate();
        delegate.onTerraform(() -> {
            // 대리자가 슬립하더라도 호출 스레드가 이미 인터럽트되어 future.get()이 즉시 InterruptedException을 던진다.
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "never-reached";
        });
        TimeBoundedInfraManagerClient decorator = newDecorator(delegate);

        Thread.currentThread().interrupt(); // 호출 전 인터럽트 사전 설정
        assertThatThrownBy(() -> decorator.runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallInterruptedException.class);

        // 데코레이터가 인터럽트 플래그를 복원했으므로 true여야 한다(소비하여 정리).
        assertThat(Thread.interrupted()).isTrue();
    }

    /**
     * 대리자가 {@link StackOverflowError}를 던지면, 데코레이터가 이를 {@code RuntimeException}으로
     * 감싸지 않고 {@code Error} 그대로 전파한다.
     */
    @Test
    void aFatalErrorFromTheDelegatePropagatesAsError() {
        ScriptableDelegate delegate = new ScriptableDelegate();
        delegate.onTerraform(() -> { throw new StackOverflowError("boom"); });
        TimeBoundedInfraManagerClient decorator = newDecorator(delegate);

        assertThatThrownBy(() -> decorator.runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(StackOverflowError.class)
                .hasMessage("boom");
    }

    /**
     * 대리자가 {@link InfraManagerClient.CallFailedException}을 던지면,
     * 폐쇄 어휘(closed vocabulary)가 보존되어 동일한 예외가 변환 없이 전파된다.
     */
    @Test
    void aCallFailedFromTheDelegatePassesThrough() {
        ScriptableDelegate delegate = new ScriptableDelegate();
        delegate.onTerraform(() -> { throw new InfraManagerClient.CallFailedException("x"); });
        TimeBoundedInfraManagerClient decorator = newDecorator(delegate);

        assertThatThrownBy(() -> decorator.runTerraform("t", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallFailedException.class)
                .hasMessage("x");
    }

    /**
     * 대리자가 정상적으로 값을 반환하면 데코레이터도 그 값을 그대로 반환한다.
     */
    @Test
    void aHappyCallReturnsTheDelegateValue() {
        ScriptableDelegate delegate = new ScriptableDelegate();
        delegate.onTerraform(() -> "job-1");
        TimeBoundedInfraManagerClient decorator = newDecorator(delegate);

        String result = decorator.runTerraform("t", TaskOperation.APPLY_NETWORK);

        assertThat(result).isEqualTo("job-1");
    }
}
