package com.bff.pipeline.client;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallInterruptedException;
import com.bff.pipeline.exception.CallTimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 호출별 타임아웃을 강제하는 {@link InfraManagerClient} 데코레이터다(ADR-021 Decision 5). 호출별 타임아웃
 * ({@code apiCallTimeout})은 러너가 소유한다는 ADR 계약을 실제로 구현한다 — delegate 호출을 별도 풀
 * ({@code infraManagerCallPool})에서 돌려 느린 호출이 워커 스레드를 붙잡지 못하게 하고, 마감을 넘기면
 * {@link CallTimeoutException}으로 바꾼다.
 *
 * <p>{@code @Primary}이고, 실제 전송 구현은 {@code @Qualifier("infraManagerDelegate")} 빈으로 주입한다
 * (자기참조 회피). delegate가 던지는 닫힌 어휘 예외는 그대로 흘려보내고, 그 밖의 {@code RuntimeException}은
 * 언랩해 전파한다(fail-fast). 인터럽트는 {@link CallInterruptedException}으로 바꾸면서 인터럽트 플래그를 복원한다.
 *
 * <p>delegate({@code infraManagerDelegate})는 {@code FeignConfig}가 제공하며, 데코레이터는 그것을 {@code @Qualifier}로
 * 주입받는다 — 의존성 해석이 생성 순서를 보장한다. 슬라이스 테스트(@DataJpaTest)는 이 데코레이터도 fake도 컴포넌트
 * 스캔하지 않고 fake를 직접 주입하므로 무관하다. (base-url·token은 필수 설정이라 full 컨텍스트는 그것 없이는 뜨지 않는다 —
 * {@code FeignConfig} 참조.)
 */
@Primary
@Component
public class TimeBoundedInfraManagerClient implements InfraManagerClient {

    /** 호출 소요시간 Timer 이름. Prometheus에는 infra_manager_call_seconds로 노출된다. */
    static final String INFRA_MANAGER_CALL = "infra.manager.call";
    static final String OUTCOME_TAG = "outcome";
    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_TIMEOUT = "timeout";
    static final String OUTCOME_FAILURE = "failure";

    private final InfraManagerClient delegate;
    private final ExecutorService infraManagerCallPool;
    private final ExecutionSettings executionSettings;
    private final MeterRegistry meterRegistry;

    public TimeBoundedInfraManagerClient(@Qualifier("infraManagerDelegate") InfraManagerClient delegate,
            @Qualifier("infraManagerCallPool") ExecutorService infraManagerCallPool, ExecutionSettings executionSettings,
            MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.infraManagerCallPool = infraManagerCallPool;
        this.executionSettings = executionSettings;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        return withTimeout(() -> delegate.runTerraform(target, operation));
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) {
        return withTimeout(() -> delegate.terraformJobStatus(jobId, operation));
    }

    @Override
    public String terraformJobResult(String jobId, TaskOperation operation) {
        return withTimeout(() -> delegate.terraformJobResult(jobId, operation));
    }

    @Override
    public ConditionPoll checkCondition(String target, TaskOperation operation) {
        return withTimeout(() -> delegate.checkCondition(target, operation));
    }

    @Override
    public CloudProvider cloudProvider(String target) {
        return withTimeout(() -> delegate.cloudProvider(target));
    }

    /**
     * delegate 호출을 격리 풀에서 돌리고 마감을 강제하면서, 호출 한 건의 소요시간과 결말
     * (success/timeout/failure)을 Timer로 남긴다. 셧다운 인터럽트는 호출의 성패가 아니므로 기록하지 않는다.
     */
    private <T> T withTimeout(Supplier<T> call) {
        Timer.Sample callTiming = Timer.start(meterRegistry);
        Future<T> future = infraManagerCallPool.submit(call::get);
        try {
            T result = future.get(executionSettings.apiCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
            callTiming.stop(callTimer(OUTCOME_SUCCESS));
            return result;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            callTiming.stop(callTimer(OUTCOME_TIMEOUT));
            throw new CallTimeoutException();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallInterruptedException();
        } catch (ExecutionException executionFailure) {
            callTiming.stop(callTimer(OUTCOME_FAILURE));
            throw rethrow(executionFailure.getCause());
        }
    }

    private Timer callTimer(String outcome) {
        return Timer.builder(INFRA_MANAGER_CALL).tag(OUTCOME_TAG, outcome).register(meterRegistry);
    }

    private RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;        // 닫힌 어휘(CallFailed/CallTimeout 등)와 진짜 버그를 언랩해 전파
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("InfraManager call failed", cause);
    }
}
