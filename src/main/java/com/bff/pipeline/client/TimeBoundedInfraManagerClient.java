package com.bff.pipeline.client;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p>{@code @ConditionalOnBean(name = "infraManagerDelegate")} — 실제 HTTP delegate가 등록됐을 때만 활성화된다.
 * 이 데모 repo에는 프로덕션 HTTP 구현이 없으므로(테스트는 fake를 직접 주입) delegate가 없으면 데코레이터도
 * 만들어지지 않아 컨텍스트 기동을 깨뜨리지 않는다. 프로덕션은 {@code infraManagerDelegate} 빈을 제공해 켠다.
 */
@Primary
@Component
@ConditionalOnBean(name = "infraManagerDelegate")
public class TimeBoundedInfraManagerClient implements InfraManagerClient {

    private final InfraManagerClient delegate;
    private final ExecutorService infraManagerCallPool;
    private final ExecutionSettings executionSettings;

    public TimeBoundedInfraManagerClient(@Qualifier("infraManagerDelegate") InfraManagerClient delegate,
            @Qualifier("infraManagerCallPool") ExecutorService infraManagerCallPool, ExecutionSettings executionSettings) {
        this.delegate = delegate;
        this.infraManagerCallPool = infraManagerCallPool;
        this.executionSettings = executionSettings;
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        return withTimeout(() -> delegate.runTerraform(target, operation));
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId) {
        return withTimeout(() -> delegate.terraformJobStatus(jobId));
    }

    @Override
    public boolean checkCondition(String target, TaskOperation operation) {
        return withTimeout(() -> delegate.checkCondition(target, operation));
    }

    @Override
    public CloudProvider cloudProvider(String target) {
        return withTimeout(() -> delegate.cloudProvider(target));
    }

    private <T> T withTimeout(Supplier<T> call) {
        Future<T> future = infraManagerCallPool.submit(call::get);
        try {
            return future.get(executionSettings.apiCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new CallTimeoutException();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallInterruptedException();
        } catch (ExecutionException executionFailure) {
            throw rethrow(executionFailure.getCause());
        }
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
