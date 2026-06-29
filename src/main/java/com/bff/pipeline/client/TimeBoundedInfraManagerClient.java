package com.bff.pipeline.client;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
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
 * 단일 InfraManager 호출별 타임아웃 경계(transparent per-call timeout decorator, ADR Decision 5).
 * {@code imCallPool} 스레드에서 대리자(delegate) 호출을 실행하므로 느린 외부 호출이 워커 스레드를
 * 점유하지 않는다. {@code TimeoutException} → {@link InfraManagerClient.CallTimeoutException},
 * {@code InterruptedException} → {@link InfraManagerClient.CallInterruptedException}(fail-fast,
 * {@code Thread.interrupt} 복원), {@code ExecutionException} → 원인 {@code RuntimeException} 언래핑
 * (대리자의 닫힌 어휘 보존).
 *
 * <p>대리자는 {@code @Qualifier("infraManagerDelegate")}로 주입되어 {@code @Primary} 자기 참조를 방지한다.
 * 실제 어댑터(프로덕션) 또는 테스트 fake가 대리자로 등록된다.
 */
@Primary
@Component
public class TimeBoundedInfraManagerClient implements InfraManagerClient {

    private final InfraManagerClient delegate;
    private final ExecutorService imCallPool;
    private final ExecutionSettings settings;

    public TimeBoundedInfraManagerClient(
            @Qualifier("infraManagerDelegate") InfraManagerClient delegate,
            @Qualifier("imCallPool") ExecutorService imCallPool,
            ExecutionSettings settings) {
        this.delegate = delegate;
        this.imCallPool = imCallPool;
        this.settings = settings;
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

    private <T> T withTimeout(Supplier<T> call) {
        Future<T> future = imCallPool.submit(call::get);
        try {
            return future.get(settings.apiCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CallTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallInterruptedException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            throw new IllegalStateException(cause);
        }
    }
}
