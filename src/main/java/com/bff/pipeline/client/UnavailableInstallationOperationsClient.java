package com.bff.pipeline.client;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.model.HttpExchange;
import org.springframework.stereotype.Component;

/**
 * 아직 실제 서버 계약이 연결되지 않은 운영 기본 경계다. 어떤 operation도 지원한다고 광고하지 않으며 임의 URL을
 * 호출하지 않는다. 지원 확인을 건너뛴 호출도 재시도 불가인 통제된 외부 오류로 거절한다.
 */
@Component("installationOperationsDelegate")
public class UnavailableInstallationOperationsClient implements InstallationOperationsClient {
    public boolean supports(CloudProvider provider, TaskOperation operation) { return false; }
    public Recommendation fetchRecommendation(Request request) { throw unavailable(); }
    public HttpExchange deleteConfirmedResources(Request request) { throw unavailable(); }
    public HttpExchange confirmResources(ConfirmationRequest request) { throw unavailable(); }

    public TestConnectionStartResponse startOrRecoverTestConnection(Request request) { throw unavailable(); }
    public TestConnectionPollResponse pollTestConnection(Request request, String executionVersion) { throw unavailable(); }

    private CallFailedException unavailable() {
        return new CallFailedException("Installation operation unavailable", false, null);
    }
}
