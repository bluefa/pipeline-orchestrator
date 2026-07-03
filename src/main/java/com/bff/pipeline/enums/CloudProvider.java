package com.bff.pipeline.enums;

import java.util.Optional;

/**
 * target이 속한 cloud provider다. targetSourceId로 InfraManagerClient에 조회해 얻으며(설계
 * docs/task-catalog-extension-plan.md §3), recipe 선택·라우팅·표시용 메타데이터로 쓴다. single-owner 불변식의
 * 격리 축은 아니다 — target 하나당 running 하나는 active_target 유니크 제약이 그대로 보장한다.
 */
public enum CloudProvider {
    AWS,
    GCP,
    AZURE,
    IDC;

    /**
     * 저장된 cloud_provider 이름(String)을 상수로 해석한다. 미해석(추가/제거된 옛 provider)은 예외 대신
     * empty를 돌려주어 read가 터지지 않게 한다 — TaskOperation.find와 같은 열화 규약이며,
     * CloudProviderConverter가 이 해석으로 행을 읽는다.
     */
    public static Optional<CloudProvider> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(CloudProvider.valueOf(name));
        } catch (IllegalArgumentException notAProvider) {
            return Optional.empty();
        }
    }
}
