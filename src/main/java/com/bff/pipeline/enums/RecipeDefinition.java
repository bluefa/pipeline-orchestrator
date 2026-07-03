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
 * 4개 provider × install/delete 전 조합을 둔다. install은 실행 단위마다 plan → apply 순으로 잇고(plan step을
 * recipe에 포함한다는 owner 결정, 설계 §5), 실 명세가 서버 강제로 못박은 apply 순서(GCP 서비스 → BDC,
 * IDC CX → BDP)를 따른다. delete는 install의 역순으로 destroy만 수행한다 — GCP는 BDC → 서비스가 서버 강제이고,
 * IDC는 명시가 없어 역순(BDP → CX)으로 가정한다. 준비 확인(NETWORK_READY) step은 AWS에만 있다 — 타 provider의
 * ready 확인 API가 미확정이라, 확정되면 provider별 정의를 추가하고 _V2 recipe로 잇는다. AWS BDC 단위
 * (common/service-level)는 네트워크 install/delete flow에 속하지 않아 recipe 없이 정의만 존재한다.
 */
public enum RecipeDefinition {

    AWS_NETWORK_INSTALL_V1(CloudProvider.AWS, PipelineType.INSTALL,
            "AWS 네트워크 설치", "AWS 네트워크를 Terraform plan·apply로 구성하고 준비 완료를 기다린다.",
            List.of(TaskDefinition.AWS_SERVICE_PLAN_V1, TaskDefinition.AWS_SERVICE_APPLY_V1,
                    TaskDefinition.NETWORK_READY_V1)),

    AWS_NETWORK_DELETE_V1(CloudProvider.AWS, PipelineType.DELETE,
            "AWS 네트워크 삭제", "AWS 네트워크를 Terraform destroy로 제거한다.",
            List.of(TaskDefinition.AWS_SERVICE_DESTROY_V1)),

    GCP_INSTALL_V1(CloudProvider.GCP, PipelineType.INSTALL,
            "GCP 인프라 설치", "GCP 서비스와 BDC 인프라를 단위별 Terraform plan·apply로 구성한다. apply 순서는 서비스 → BDC(서버 강제).",
            List.of(TaskDefinition.GCP_SERVICE_PLAN_V1, TaskDefinition.GCP_SERVICE_APPLY_V1,
                    TaskDefinition.GCP_BDC_PLAN_V1, TaskDefinition.GCP_BDC_APPLY_V1)),

    GCP_DELETE_V1(CloudProvider.GCP, PipelineType.DELETE,
            "GCP 인프라 삭제", "GCP BDC와 서비스 인프라를 Terraform destroy로 제거한다. destroy 순서는 BDC → 서비스(서버 강제).",
            List.of(TaskDefinition.GCP_BDC_DESTROY_V1, TaskDefinition.GCP_SERVICE_DESTROY_V1)),

    AZURE_INSTALL_V1(CloudProvider.AZURE, PipelineType.INSTALL,
            "Azure 인프라 설치", "Azure BDC 인프라를 Terraform plan·apply로 구성한다.",
            List.of(TaskDefinition.AZURE_BDC_PLAN_V1, TaskDefinition.AZURE_BDC_APPLY_V1)),

    AZURE_DELETE_V1(CloudProvider.AZURE, PipelineType.DELETE,
            "Azure 인프라 삭제", "Azure BDC 인프라를 Terraform destroy로 제거한다.",
            List.of(TaskDefinition.AZURE_BDC_DESTROY_V1)),

    IDC_INSTALL_V1(CloudProvider.IDC, PipelineType.INSTALL,
            "IDC 인프라 설치", "IDC CX와 BDP 인프라를 단위별 Terraform plan·apply로 구성한다. apply 순서는 CX → BDP(서버 강제).",
            List.of(TaskDefinition.IDC_CX_PLAN_V1, TaskDefinition.IDC_CX_APPLY_V1,
                    TaskDefinition.IDC_BDP_PLAN_V1, TaskDefinition.IDC_BDP_APPLY_V1)),

    IDC_DELETE_V1(CloudProvider.IDC, PipelineType.DELETE,
            "IDC 인프라 삭제", "IDC BDP와 CX 인프라를 Terraform destroy로 제거한다(BDP destroy는 pod 삭제 동반, 순서는 설치의 역순 가정).",
            List.of(TaskDefinition.IDC_BDP_DESTROY_V1, TaskDefinition.IDC_CX_DESTROY_V1));

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
