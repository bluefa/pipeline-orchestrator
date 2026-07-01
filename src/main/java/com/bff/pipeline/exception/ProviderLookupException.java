package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * create 시점의 cloud provider 조회(InfraManagerClient)가 인프라 실패(타임아웃/호출 오류)로 실패했다. 비즈니스
 * 실패가 아니라 외부 의존성 장애이므로 503 Service Unavailable + code {@code ORCHESTRATION_PROVIDER_LOOKUP_FAILED}로
 * 매핑한다(설계 §3, exception-strategy).
 */
public class ProviderLookupException extends OrchestrationException {

    public ProviderLookupException(String target, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, OrchestrationErrorCode.PROVIDER_LOOKUP_FAILED,
                "cloud provider lookup failed for target " + target, cause);
    }
}
