package com.bff.pipeline.enums;

import java.util.List;
import java.util.Optional;

/**
 * (cloud provider, pipeline type)별 파이프라인 정체성 카탈로그다(설계 docs/task-catalog-extension-plan.md §2).
 * 각 항목은 명명·열거 가능하고 metadata를 실어 Admin API로 노출된다. steps는 순서 있는 TaskDefinition 목록이다.
 *
 * 버전 불변 규약: TaskDefinition과 동일하게 이름에 버전(_V1)을 박아 의미를 불변으로 둔다. Pipeline 행은 이 상수
 * 이름(String)을 저장한다.
 *
 * 지금은 AWS install/delete 두 항목만 둔다 — AWS 서비스 terraform 실행 단위(plan → apply) 기준이며, plan step은
 * 변경 내용 산출을 recipe에 포함한다는 owner 결정(설계 §5)을 반영한다. GCP/AZURE/IDC와 BDC 단위까지 잇는 step
 * 목록은 provider별 실행 flow가 확정되면 항목으로 추가한다(client 카탈로그는 전 operation을 이미 바인딩한다).
 */
public enum RecipeDefinition {

    AWS_NETWORK_INSTALL_V1(CloudProvider.AWS, PipelineType.INSTALL,
            "AWS 네트워크 설치", "AWS 네트워크 변경을 계획·적용하고 준비 완료를 기다린다.",
            List.of(TaskDefinition.AWS_SERVICE_PLAN_V1, TaskDefinition.AWS_SERVICE_APPLY_V1,
                    TaskDefinition.NETWORK_READY_V1)),

    AWS_NETWORK_DELETE_V1(CloudProvider.AWS, PipelineType.DELETE,
            "AWS 네트워크 삭제", "AWS 네트워크를 철거한다.",
            List.of(TaskDefinition.AWS_SERVICE_DESTROY_V1));

    private final CloudProvider provider;
    private final PipelineType pipelineType;
    private final String displayName;
    private final String description;
    private final List<TaskDefinition> steps;

    RecipeDefinition(CloudProvider provider, PipelineType pipelineType, String displayName, String description,
            List<TaskDefinition> steps) {
        this.provider = provider;
        this.pipelineType = pipelineType;
        this.displayName = displayName;
        this.description = description;
        this.steps = List.copyOf(steps);
    }

    public CloudProvider provider() {
        return provider;
    }

    public PipelineType pipelineType() {
        return pipelineType;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public List<TaskDefinition> steps() {
        return steps;
    }

    /** 저장된 recipe 이름(String)을 상수로 해석한다 — 미해석은 예외 대신 empty(카탈로그 삭제/rename에 관대). */
    public static Optional<RecipeDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RecipeDefinition.valueOf(name));
        } catch (IllegalArgumentException notARecipe) {
            return Optional.empty();
        }
    }
}
