package com.bff.pipeline.enums;

/**
 * target이 속한 cloud provider다. targetSourceId로 InfraManagerClient에 조회해 얻으며(설계
 * docs/task-catalog-extension-plan.md §3), recipe 선택·라우팅·표시용 메타데이터로 쓴다. single-owner 불변식의
 * 격리 축은 아니다 — target 하나당 running 하나는 active_target 유니크 제약이 그대로 보장한다.
 */
public enum CloudProvider {
    AWS,
    GCP,
    AZURE,
    IDC
}
