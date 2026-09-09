package com.bff.pipeline.client;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.HttpExchange;
import lombok.Builder;

/**
 * 설치 업무 서버와의 실제 외부 경계다. 운영 구현은 서비스 인증, 요청 키의 멱등성, 승인/대상 세대의 검증을
 * 입증한 operation만 지원한다고 표시한다. 공통 Task의 구성 범위와 실제 CSP별 호출 가능 여부는 다르다.
 * 추천 JSON에 임의 필드를 추가하지 않고 검증한 맥락을 별도 값으로 전달한다. 테스트는 이 경계에 fake를 넣는다.
 */
public interface InstallationOperationsClient {
    boolean supports(CloudProvider provider, TaskOperation operation);
    default boolean supportsReconfirmation(CloudProvider provider) { return false; }
    Recommendation fetchRecommendation(Request request);
    HttpExchange deleteConfirmedResources(Request request);
    HttpExchange confirmResources(ConfirmationRequest request);

    @Builder
    record Request(long taskId, String target, CloudProvider provider, String requestKey) { }
    @Builder
    record VerifiedContext(String approvalVersion, String targetGeneration) { }
    record Recommendation(HttpExchange exchange, VerifiedContext context) { }
    @Builder
    record ConfirmationRequest(Request request, String body, VerifiedContext context,
            boolean applyNlbSecurityGroup) { }
}
