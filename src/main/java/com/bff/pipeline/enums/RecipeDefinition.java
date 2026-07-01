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
 * 데모: 지금은 AWS install/delete 두 항목만 placeholder로 둔다. GCP/AZURE/IDC와 provider별로 다른 step 목록은
 * 실제 도메인 데이터가 정해지면 항목으로 추가한다(각 provider의 step 수·사용 task가 다르다).
 */
public enum RecipeDefinition {

    AWS_NETWORK_INSTALL_V1(CloudProvider.AWS, PipelineType.INSTALL,
            "AWS 네트워크 설치", "AWS 네트워크를 생성하고 준비 완료를 기다린다.",
            List.of(TaskDefinition.APPLY_NETWORK_V1, TaskDefinition.NETWORK_READY_V1)),

    AWS_NETWORK_DELETE_V1(CloudProvider.AWS, PipelineType.DELETE,
            "AWS 네트워크 삭제", "AWS 네트워크를 철거한다.",
            List.of(TaskDefinition.DESTROY_NETWORK_V1));

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
