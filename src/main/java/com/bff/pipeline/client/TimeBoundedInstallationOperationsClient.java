package com.bff.pipeline.client;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.HttpExchange;
import com.bff.pipeline.utils.BoundedCallExecutor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 공통 설치 서버 호출에 기존 호출별 시간 상한을 적용한다. capability는 네트워크 요청이 없는 로컬 계약 조회다.
 * 실제 GET/DELETE/POST는 각각 한 번의 제한된 호출로 실행하며 인터럽트와 외부 오류 종류를 보존한다.
 */
@Primary
@Component
public class TimeBoundedInstallationOperationsClient implements InstallationOperationsClient {
    private final InstallationOperationsClient delegate;
    private final ExecutorService pool;
    private final ExecutionSettings settings;

    public TimeBoundedInstallationOperationsClient(
            @Qualifier("installationOperationsDelegate") InstallationOperationsClient delegate,
            @Qualifier("infraManagerCallPool") ExecutorService pool, ExecutionSettings settings) {
        this.delegate = delegate;
        this.pool = pool;
        this.settings = settings;
    }

    public boolean supports(CloudProvider provider, TaskOperation operation) { return delegate.supports(provider, operation); }
    public boolean supportsReconfirmation(CloudProvider provider) { return delegate.supportsReconfirmation(provider); }
    public Recommendation fetchRecommendation(Request request) { return call(() -> delegate.fetchRecommendation(request)); }
    public HttpExchange deleteConfirmedResources(Request request) { return call(() -> delegate.deleteConfirmedResources(request)); }
    public HttpExchange confirmResources(ConfirmationRequest request) { return call(() -> delegate.confirmResources(request)); }

    public TestConnectionStartResponse startOrRecoverTestConnection(Request request) {
        return call(() -> delegate.startOrRecoverTestConnection(request));
    }
    public TestConnectionPollResponse pollTestConnection(Request request, String executionVersion) {
        return call(() -> delegate.pollTestConnection(request, executionVersion));
    }

    private <T> T call(Supplier<T> call) {
        return BoundedCallExecutor.execute(pool, settings.apiCallTimeout(), call);
    }
}
