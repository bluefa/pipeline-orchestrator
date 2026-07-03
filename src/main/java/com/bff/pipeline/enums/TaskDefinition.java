package com.bff.pipeline.enums;

import com.bff.pipeline.client.terraform.TerraformJobType;
import java.util.Optional;

/**
 * task의 명명된 1급 정체성 카탈로그다(설계: docs/task-catalog-extension-plan.md §1). 느슨한
 * (taskName, operation) 쌍 대신, 각 항목이 "이 task가 정확히 무슨 역할인지"를 이름으로 못박고 실행 계약
 * metadata({@link TaskExecutionSpec} — 호출 API, 성공 판정 정책, result 저장 방식)를 실어 Admin API로 노출된다.
 * 고유 축은 provider + operation + metadata뿐이다 — mechanism과 slot 소비 여부는 operation이 유일하게
 * 결정하므로(operation이 mechanism을 결정한다) operation에서 파생만 한다. 항목은 확정된 InfraManager Terraform
 * 실 명세의 전 operation을 덮는다 — recipe가 아직 쓰지 않는 항목도 카탈로그에는 존재한다(recipe 채택은
 * provider별 flow 확정 후).
 *
 * 버전 불변 규약: 상수 이름에 버전(_V1)을 박아 의미를 불변으로 둔다 — V1의 provider/operation은 절대 바꾸지 않고,
 * 바뀌면 _V2를 추가한다. Task 행은 이 상수 이름(String)을 진실원으로 저장하므로, 배포 사이에 정의가 바뀌어 in-flight
 * 행의 실행 의미가 달라지는 일이 없다. find는 미해석(삭제/rename된 옛 이름)을 예외가 아니라 Optional.empty()로 돌려주어,
 * 엔진이 ErrorCode.UNKNOWN_TASK로 깨끗이 열화하게 한다.
 */
public enum TaskDefinition {

    // ══ AWS 서비스 Terraform ══

    AWS_SERVICE_PLAN_V1(CloudProvider.AWS, TaskOperation.AWS_SERVICE_TF_PLAN,
            "네트워크 변경 계획", "대상 네트워크 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/terraform-jobs/plan",
                    "GET /infra/terraform-jobs/plan/{terraformJobId}",
                    "GET /infra/terraform-jobs/plan/{terraformJobId}/result")),
    AWS_SERVICE_APPLY_V1(CloudProvider.AWS, TaskOperation.AWS_SERVICE_TF_APPLY,
            "네트워크 생성", "대상의 네트워크 인프라를 Terraform으로 구성(apply)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/terraform-jobs/apply",
                    "GET /infra/terraform-jobs/apply/{terraformJobId}",
                    "GET /infra/terraform-jobs/apply/{terraformJobId}/result")),
    AWS_SERVICE_DESTROY_V1(CloudProvider.AWS, TaskOperation.AWS_SERVICE_TF_DESTROY,
            "네트워크 철거", "대상의 네트워크 인프라를 Terraform으로 철거(destroy)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "DELETE /infra/target-sources/{targetSourceId}/terraform-jobs/destroy",
                    "GET /infra/terraform-jobs/destroy/{terraformJobId}",
                    "GET /infra/terraform-jobs/destroy/{terraformJobId}/result")),

    // ══ AWS BDC Service Level Common Terraform ══

    AWS_BDC_COMMON_PLAN_V1(CloudProvider.AWS, TaskOperation.AWS_BDC_COMMON_TF_PLAN,
            "AWS BDC 공통 변경 계획", "AWS BDC service level common 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/aws/service/level/common/terraform/plan",
                    "GET /infra/aws/service/level/common/terraform/{terraformJobId}?jobType=PLAN",
                    "GET /infra/aws/service/level/common/terraform/{terraformJobId}/result?jobType=PLAN")),
    AWS_BDC_COMMON_APPLY_V1(CloudProvider.AWS, TaskOperation.AWS_BDC_COMMON_TF_APPLY,
            "AWS BDC 공통 구성", "AWS BDC service level common 인프라를 Terraform으로 구성(apply)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/aws/service/level/common/terraform/apply",
                    "GET /infra/aws/service/level/common/terraform/{terraformJobId}?jobType=APPLY",
                    "GET /infra/aws/service/level/common/terraform/{terraformJobId}/result?jobType=APPLY")),
    AWS_BDC_COMMON_DESTROY_V1(CloudProvider.AWS, TaskOperation.AWS_BDC_COMMON_TF_DESTROY,
            "AWS BDC 공통 철거", "AWS BDC service level common 인프라를 Terraform으로 철거(destroy)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "POST /infra/target-sources/{targetSourceId}/aws/service/level/common/terraform/destroy",
                    "GET /infra/aws/service/level/common/terraform/{terraformJobId}?jobType=DESTROY",
                    "GET /infra/aws/service/level/common/terraform/{terraformJobId}/result?jobType=DESTROY")),

    // ══ AWS BDC Service Level Terraform (destroy dispatch의 실 경로명은 "remove") ══

    AWS_BDC_SERVICE_LEVEL_PLAN_V1(CloudProvider.AWS, TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_PLAN,
            "AWS BDC 서비스 레벨 변경 계획", "AWS BDC service level 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/bdc-service-level-terraform-jobs/plan",
                    "GET /infra/bdc-service-level-terraform-jobs/plan/{terraformJobId}",
                    "GET /infra/bdc-service-level-terraform-jobs/plan/{terraformJobId}/result")),
    AWS_BDC_SERVICE_LEVEL_APPLY_V1(CloudProvider.AWS, TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_APPLY,
            "AWS BDC 서비스 레벨 구성", "AWS BDC service level 인프라를 Terraform으로 구성(apply)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/bdc-service-level-terraform-jobs/apply",
                    "GET /infra/bdc-service-level-terraform-jobs/action/{terraformJobId}?jobType=APPLY",
                    "GET /infra/bdc-service-level-terraform-jobs/action/{terraformJobId}/result?jobType=APPLY")),
    AWS_BDC_SERVICE_LEVEL_DESTROY_V1(CloudProvider.AWS, TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_DESTROY,
            "AWS BDC 서비스 레벨 철거", "AWS BDC service level 인프라를 Terraform으로 철거(destroy)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "DELETE /infra/target-sources/{targetSourceId}/bdc-service-level-terraform-jobs/remove",
                    "GET /infra/bdc-service-level-terraform-jobs/action/{terraformJobId}?jobType=DESTROY",
                    "GET /infra/bdc-service-level-terraform-jobs/action/{terraformJobId}/result?jobType=DESTROY")),

    // ══ GCP 서비스 Terraform ══

    GCP_SERVICE_PLAN_V1(CloudProvider.GCP, TaskOperation.GCP_SERVICE_TF_PLAN,
            "GCP 서비스 변경 계획", "GCP 서비스 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/gcp/service-terraform-jobs?jobType=PLAN",
                    "GET /infra/gcp/service-terraform-jobs/{terraformJobId}?jobType=PLAN",
                    "GET /infra/gcp/service-terraform-jobs/{terraformJobId}/result?jobType=PLAN")),
    GCP_SERVICE_APPLY_V1(CloudProvider.GCP, TaskOperation.GCP_SERVICE_TF_APPLY,
            "GCP 서비스 구성", "GCP 서비스 인프라를 Terraform으로 구성(apply)한다. BDC 구성보다 먼저 수행돼야 한다(서버 강제).",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/gcp/service-terraform-jobs?jobType=APPLY",
                    "GET /infra/gcp/service-terraform-jobs/{terraformJobId}?jobType=APPLY",
                    "GET /infra/gcp/service-terraform-jobs/{terraformJobId}/result?jobType=APPLY")),
    GCP_SERVICE_DESTROY_V1(CloudProvider.GCP, TaskOperation.GCP_SERVICE_TF_DESTROY,
            "GCP 서비스 철거", "GCP 서비스 인프라를 Terraform으로 철거(destroy)한다. BDC 철거 이후에 수행돼야 한다(서버 강제).",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "POST /infra/target-sources/{targetSourceId}/gcp/service-terraform-jobs?jobType=DESTROY",
                    "GET /infra/gcp/service-terraform-jobs/{terraformJobId}?jobType=DESTROY",
                    "GET /infra/gcp/service-terraform-jobs/{terraformJobId}/result?jobType=DESTROY")),

    // ══ GCP BDC Terraform ══

    GCP_BDC_PLAN_V1(CloudProvider.GCP, TaskOperation.GCP_BDC_TF_PLAN,
            "GCP BDC 변경 계획", "GCP BDC 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/gcp/bdc-terraform-jobs?jobType=PLAN",
                    "GET /infra/gcp/bdc-terraform-jobs/{terraformJobId}?jobType=PLAN",
                    "GET /infra/gcp/bdc-terraform-jobs/{terraformJobId}/result?jobType=PLAN")),
    GCP_BDC_APPLY_V1(CloudProvider.GCP, TaskOperation.GCP_BDC_TF_APPLY,
            "GCP BDC 구성", "GCP BDC 인프라를 Terraform으로 구성(apply)한다. 서비스 구성 이후에 수행돼야 한다(서버 강제).",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/gcp/bdc-terraform-jobs?jobType=APPLY",
                    "GET /infra/gcp/bdc-terraform-jobs/{terraformJobId}?jobType=APPLY",
                    "GET /infra/gcp/bdc-terraform-jobs/{terraformJobId}/result?jobType=APPLY")),
    GCP_BDC_DESTROY_V1(CloudProvider.GCP, TaskOperation.GCP_BDC_TF_DESTROY,
            "GCP BDC 철거", "GCP BDC 인프라를 Terraform으로 철거(destroy)한다. 서비스 철거보다 먼저 수행돼야 한다(서버 강제).",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "POST /infra/target-sources/{targetSourceId}/gcp/bdc-terraform-jobs?jobType=DESTROY",
                    "GET /infra/gcp/bdc-terraform-jobs/{terraformJobId}?jobType=DESTROY",
                    "GET /infra/gcp/bdc-terraform-jobs/{terraformJobId}/result?jobType=DESTROY")),

    // ══ Azure BDC Terraform (destroy dispatch만 /azure prefix가 없다 — 실 명세 그대로) ══

    AZURE_BDC_PLAN_V1(CloudProvider.AZURE, TaskOperation.AZURE_BDC_TF_PLAN,
            "Azure BDC 변경 계획", "Azure BDC 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/azure/target-sources/{targetSourceId}/azure/terraform-jobs/plan",
                    "GET /infra/azure/terraform-jobs/plan/{terraformJobId}",
                    "GET /infra/azure/terraform-jobs/plan/{terraformJobId}/result")),
    AZURE_BDC_APPLY_V1(CloudProvider.AZURE, TaskOperation.AZURE_BDC_TF_APPLY,
            "Azure BDC 구성", "Azure BDC 인프라를 Terraform으로 구성(apply)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/azure/target-sources/{targetSourceId}/azure/terraform-jobs/apply",
                    "GET /infra/azure/terraform-jobs/apply/{terraformJobId}",
                    "GET /infra/azure/terraform-jobs/apply/{terraformJobId}/result")),
    AZURE_BDC_DESTROY_V1(CloudProvider.AZURE, TaskOperation.AZURE_BDC_TF_DESTROY,
            "Azure BDC 철거", "Azure BDC 인프라를 Terraform으로 철거(destroy)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "DELETE /infra/target-sources/{targetSourceId}/azure/terraform-jobs/destroy",
                    "GET /infra/azure/terraform-jobs/destroy/{terraformJobId}",
                    "GET /infra/azure/terraform-jobs/destroy/{terraformJobId}/result")),

    // ══ IDC Terraform — CX ══

    IDC_CX_PLAN_V1(CloudProvider.IDC, TaskOperation.IDC_CX_TF_PLAN,
            "IDC CX 변경 계획", "IDC CX 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/idc/terraform/action?jobType=PLAN&idcTerraformType=CX",
                    "GET /infra/idc/terraform/{terraformJobId}?jobType=PLAN",
                    "GET /infra/idc/terraform/{terraformJobId}/result?jobType=PLAN")),
    IDC_CX_APPLY_V1(CloudProvider.IDC, TaskOperation.IDC_CX_TF_APPLY,
            "IDC CX 구성", "IDC CX 인프라를 Terraform으로 구성(apply)한다. BDP 구성보다 먼저 수행돼야 한다(서버 강제).",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/idc/terraform/action?jobType=APPLY&idcTerraformType=CX",
                    "GET /infra/idc/terraform/{terraformJobId}?jobType=APPLY",
                    "GET /infra/idc/terraform/{terraformJobId}/result?jobType=APPLY")),
    IDC_CX_DESTROY_V1(CloudProvider.IDC, TaskOperation.IDC_CX_TF_DESTROY,
            "IDC CX 철거", "IDC CX 인프라를 Terraform으로 철거(destroy)한다.",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "POST /infra/target-sources/{targetSourceId}/idc/terraform/action?jobType=DESTROY&idcTerraformType=CX",
                    "GET /infra/idc/terraform/{terraformJobId}?jobType=DESTROY",
                    "GET /infra/idc/terraform/{terraformJobId}/result?jobType=DESTROY")),

    // ══ IDC Terraform — BDP ══

    IDC_BDP_PLAN_V1(CloudProvider.IDC, TaskOperation.IDC_BDP_TF_PLAN,
            "IDC BDP 변경 계획", "IDC BDP 인프라의 Terraform plan을 실행해 변경 내용을 산출한다.",
            TaskExecutionSpec.terraform(TerraformJobType.PLAN,
                    "POST /infra/target-sources/{targetSourceId}/idc/terraform/action?jobType=PLAN&idcTerraformType=BDP",
                    "GET /infra/idc/terraform/{terraformJobId}?jobType=PLAN",
                    "GET /infra/idc/terraform/{terraformJobId}/result?jobType=PLAN")),
    IDC_BDP_APPLY_V1(CloudProvider.IDC, TaskOperation.IDC_BDP_TF_APPLY,
            "IDC BDP 구성", "IDC BDP 인프라를 Terraform으로 구성(apply)한다. CX 구성 이후에 수행돼야 한다(서버 강제).",
            TaskExecutionSpec.terraform(TerraformJobType.APPLY,
                    "POST /infra/target-sources/{targetSourceId}/idc/terraform/action?jobType=APPLY&idcTerraformType=BDP",
                    "GET /infra/idc/terraform/{terraformJobId}?jobType=APPLY",
                    "GET /infra/idc/terraform/{terraformJobId}/result?jobType=APPLY")),
    IDC_BDP_DESTROY_V1(CloudProvider.IDC, TaskOperation.IDC_BDP_TF_DESTROY,
            "IDC BDP 철거", "IDC BDP 인프라를 Terraform으로 철거(destroy)한다. pod 삭제를 동반한다.",
            TaskExecutionSpec.terraform(TerraformJobType.DESTROY,
                    "POST /infra/target-sources/{targetSourceId}/idc/terraform/action?jobType=DESTROY&idcTerraformType=BDP",
                    "GET /infra/idc/terraform/{terraformJobId}?jobType=DESTROY",
                    "GET /infra/idc/terraform/{terraformJobId}/result?jobType=DESTROY")),

    // ══ CONDITION_CHECK (실제 API 미확정 — 가정 엔드포인트) ══

    NETWORK_READY_V1(CloudProvider.AWS, TaskOperation.NETWORK_READY,
            "네트워크 준비 확인", "네트워크가 준비 완료 상태가 될 때까지 조건을 확인한다.",
            TaskExecutionSpec.conditionCheck("GET /infra/network/ready?target={target} (실제 API 미확정 — 가정 엔드포인트)"));

    private final CloudProvider provider;
    private final TaskOperation operation;
    private final String displayName;
    private final String description;
    private final TaskExecutionSpec spec;

    TaskDefinition(CloudProvider provider, TaskOperation operation, String displayName, String description,
            TaskExecutionSpec spec) {
        this.provider = provider;
        this.operation = operation;
        this.displayName = displayName;
        this.description = description;
        this.spec = spec;
    }

    public CloudProvider provider() {
        return provider;
    }

    public TaskOperation operation() {
        return operation;
    }

    /** 이 정의를 실행할 TaskType의 이름(=taskName). operation이 결정한다. */
    public String mechanism() {
        return operation.mechanism();
    }

    /** terraform slot 소비 여부. operation이 결정한다. */
    public boolean consumesTerraformSlot() {
        return operation.consumesTerraformSlot();
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** 실행 계약 metadata — 호출 API, 성공 판정 정책, result 저장 방식(Admin 노출용). */
    public TaskExecutionSpec spec() {
        return spec;
    }

    /**
     * 저장된 정의 이름(String)을 상수로 해석한다. 미해석(삭제/rename된 옛 이름)은 예외 대신 empty를 돌려주어
     * 호출자가 UNKNOWN_TASK로 열화하게 한다. @Enumerated가 아니라 이 수동 해석을 쓰는 이유다.
     */
    public static Optional<TaskDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TaskDefinition.valueOf(name));
        } catch (IllegalArgumentException notADefinition) {
            return Optional.empty();
        }
    }
}
