package com.bff.pipeline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 연결 테스트의 실행 제한 시간, 관찰 간격, 접수 재시도 예산을 정한다. 생성 시 Task에 고정하고 실제 마감은
 * 최초 READY 시각을 기준으로 한 번만 저장한다. 폴 통신 오류 횟수는 별도 종결 기준으로 사용하지 않는다.
 */
@ConfigurationProperties(prefix = "pipeline.test-connection")
public record TestConnectionSettings(@DefaultValue("PT50M") Duration executionTimeout,
        @DefaultValue("PT4S") Duration pollingInterval, @DefaultValue("24") int maxFailCount) {
    public TestConnectionSettings {
        if (executionTimeout == null || !executionTimeout.isPositive()) {
            throw new IllegalArgumentException("pipeline.test-connection.execution-timeout must be positive");
        }
        if (pollingInterval == null || !pollingInterval.isPositive() || maxFailCount < 1) {
            throw new IllegalArgumentException("pipeline.test-connection polling interval and retry budget must be positive");
        }
    }
}
