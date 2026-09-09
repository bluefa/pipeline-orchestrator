package com.bff.pipeline.config;

import com.bff.pipeline.enums.TaskOperation;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공통 설치 작업의 활성 operation과 HTTP 크기/재시도 제한을 관리한다. 기본 활성 집합은 비어 있으므로 실제
 * 서버 계약이 없는 배포에서 자동 쓰기가 시작되지 않는다. 설정 활성화와 실제 client 지원을 모두 확인해야 한다.
 */
@ConfigurationProperties(prefix = "pipeline.installation")
public record InstallationSettings(@DefaultValue Set<TaskOperation> enabledOperations,
        @DefaultValue("1048576") int httpResponseMaxBytes,
        @DefaultValue("1048576") int confirmationInputMaxBytes,
        @DefaultValue("PT5S") Duration httpRetryInterval,
        @DefaultValue("24") int httpMaxFailCount) {
    public InstallationSettings {
        enabledOperations = enabledOperations == null ? Set.of() : Set.copyOf(enabledOperations);
        if (httpResponseMaxBytes < 1 || confirmationInputMaxBytes < 1 || httpMaxFailCount < 1) {
            throw new IllegalArgumentException("installation byte limits and retry budget must be positive");
        }
        if (httpRetryInterval == null || !httpRetryInterval.isPositive()) {
            throw new IllegalArgumentException("installation.http-retry-interval must be positive");
        }
    }
}
