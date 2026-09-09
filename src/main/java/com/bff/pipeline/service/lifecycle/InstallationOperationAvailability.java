package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.client.InstallationOperationsClient;
import com.bff.pipeline.config.InstallationSettings;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.exception.InstallationRequestException;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CSP별 외부 capability와 명시적 operation 활성 설정의 교집합을 판단한다. 공통 정의의 전체 catalog 표시는
 * 모든 CSP에서 실행 가능한 경우에만 available이다. 특정 CSP 조회와 실제 생성은 그 CSP만 검증한다.
 */
@Component
@RequiredArgsConstructor
public class InstallationOperationAvailability {
    private final InstallationSettings settings;
    private final InstallationOperationsClient client;

    public boolean isAvailable(CloudProvider provider, TaskDefinition definition) {
        if (provider == null) {
            return Arrays.stream(CloudProvider.values()).filter(definition::supportsProvider)
                    .allMatch(candidate -> isAvailable(candidate, definition));
        }
        if (!definition.supportsProvider(provider)) return false;
        if (!definition.operation().usesInstallationClient()) return true;
        return settings.enabledOperations().contains(definition.operation()) && client.supports(provider, definition.operation());
    }

    public boolean supportsReconfirmation(CloudProvider provider) { return client.supportsReconfirmation(provider); }

    public void requireAvailable(CloudProvider provider, TaskDefinition definition) {
        if (!isAvailable(provider, definition)) throw InstallationRequestException.unavailable(definition.name());
    }

}
