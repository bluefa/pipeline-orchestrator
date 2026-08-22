package com.bff.pipeline.enums;

import com.bff.pipeline.client.terraform.TerraformJobType;
import java.util.Optional;

/**
 * 태스크가 수행하는 도메인 액션이다(ADR-016 §2). 각 operation은 자기를 실행할 mechanism(=TaskType 이름)을 소유한다 —
 * operation이 mechanism을 유일하게 결정하기 때문이다(mechanism이 다른데 operation이 같은 경우는 없다). 그래서 라우팅의
 * 근원은 operation이고, TaskDefinition은 operation에서 mechanism/slot을 파생만 한다.
 *
 * operation 자체는 데이터다 — 실제 dispatch/poll 동작은 TaskType 구현(TerraformTask/ConditionCheckTask)에 있고,
 * operation은 (a) 어느 mechanism으로 갈지 라우팅하고 (b) InfraManager 호출 때 구체 액션 값으로 전달된다.
 *
 * TERRAFORM_JOB operation은 확정된 InfraManager Terraform API 명세의 "실행 단위 × job 타입" 격자를 그대로 반영한다
 * (docs/terraform-client-and-postcheck-design.md §1): 실행 단위 8개(AWS 서비스/BDC common/BDC service-level,
 * GCP 서비스/BDC, Azure BDC, IDC CX/BDP) × PLAN/APPLY/DESTROY = 24. 각 operation의 실제 API 바인딩은
 * TerraformBindingCatalog의 행 하나이고, 행 누락은 InfraManagerOperationRegistry가 부팅에서 잡는다.
 *
 * mechanism은 String이라 TaskType의 열린 집합을 유지한다(새 TaskType은 이름으로 자기등록, 중앙 enum 수정 없음).
 * 값은 각 TaskType.NAME과 일치해야 하며, 부팅 시 TaskTypeRegistry가 모든 operation의 mechanism이 등록된 TaskType을
 * 가리키는지 검증한다. slot 소비 여부는 mechanism의 속성이라 여기서 mechanism으로 판별한다(slot의 단일 authority).
 */
public enum TaskOperation {

    // ── TERRAFORM_JOB mechanism — AWS ──
    AWS_SERVICE_TF_PLAN(Mechanism.TERRAFORM_JOB),
    AWS_SERVICE_TF_APPLY(Mechanism.TERRAFORM_JOB),
    AWS_SERVICE_TF_DESTROY(Mechanism.TERRAFORM_JOB),
    AWS_BDC_COMMON_TF_PLAN(Mechanism.TERRAFORM_JOB),
    AWS_BDC_COMMON_TF_APPLY(Mechanism.TERRAFORM_JOB),
    AWS_BDC_COMMON_TF_DESTROY(Mechanism.TERRAFORM_JOB),
    AWS_BDC_SERVICE_LEVEL_TF_PLAN(Mechanism.TERRAFORM_JOB),
    AWS_BDC_SERVICE_LEVEL_TF_APPLY(Mechanism.TERRAFORM_JOB),
    AWS_BDC_SERVICE_LEVEL_TF_DESTROY(Mechanism.TERRAFORM_JOB),

    // ── TERRAFORM_JOB mechanism — GCP (서비스 Apply → BDC Apply, BDC Destroy → 서비스 Destroy 순서는 서버가 강제) ──
    GCP_SERVICE_TF_PLAN(Mechanism.TERRAFORM_JOB),
    GCP_SERVICE_TF_APPLY(Mechanism.TERRAFORM_JOB),
    GCP_SERVICE_TF_DESTROY(Mechanism.TERRAFORM_JOB),
    GCP_BDC_TF_PLAN(Mechanism.TERRAFORM_JOB),
    GCP_BDC_TF_APPLY(Mechanism.TERRAFORM_JOB),
    GCP_BDC_TF_DESTROY(Mechanism.TERRAFORM_JOB),

    // ── TERRAFORM_JOB mechanism — Azure ──
    AZURE_BDC_TF_PLAN(Mechanism.TERRAFORM_JOB),
    AZURE_BDC_TF_APPLY(Mechanism.TERRAFORM_JOB),
    AZURE_BDC_TF_DESTROY(Mechanism.TERRAFORM_JOB),

    // ── TERRAFORM_JOB mechanism — IDC (CX → BDP 순서는 서버가 강제, BDP DESTROY는 pod 삭제 동반) ──
    IDC_CX_TF_PLAN(Mechanism.TERRAFORM_JOB),
    IDC_CX_TF_APPLY(Mechanism.TERRAFORM_JOB),
    IDC_CX_TF_DESTROY(Mechanism.TERRAFORM_JOB),
    IDC_BDP_TF_PLAN(Mechanism.TERRAFORM_JOB),
    IDC_BDP_TF_APPLY(Mechanism.TERRAFORM_JOB),
    IDC_BDP_TF_DESTROY(Mechanism.TERRAFORM_JOB),

    // ── CONDITION_CHECK mechanism ──
    /** 네트워크가 준비됐는지 확인(condition check)하는 액션. (실제 API 미확정 — 가정 엔드포인트) */
    NETWORK_READY(Mechanism.CONDITION_CHECK),

    // ── APPROVAL mechanism (승인 게이트 ADR §결정 1) ──
    /**
     * terraform apply 직전에 사람의 확인을 받는 승인 게이트 액션. 실행 단위와 무관한 단일 값이다 — 외부 API를
     * 부르지 않으므로 실행 단위별로 나눌 이유가 없고, "무엇에 동의하는가"의 구분은 apply/destroy 축뿐이다.
     * terraform slot을 쓰지 않으며 표시명은 TaskDefinition이 provider별로 담는다.
     */
    TF_APPLY_APPROVAL(Mechanism.APPROVAL);

    /** mechanism 이름 리터럴 — 값은 각 TaskType.NAME과 일치해야 하며 부팅 시 검증된다. */
    public static final class Mechanism {
        public static final String TERRAFORM_JOB = "TERRAFORM_JOB";
        public static final String CONDITION_CHECK = "CONDITION_CHECK";
        public static final String APPROVAL = "APPROVAL";

        private Mechanism() {
        }
    }

    private final String mechanism;

    TaskOperation(String mechanism) {
        this.mechanism = mechanism;
    }

    /** 이 operation을 실행할 TaskType의 이름(=taskName). */
    public String mechanism() {
        return mechanism;
    }

    /**
     * terraform 액션 라벨(PLAN/APPLY/DESTROY)이다 — 운영 UI가 job 노드에 태그로 표시한다. TERRAFORM_JOB
     * operation의 이름 규약(…_TF_PLAN/…_TF_APPLY/…_TF_DESTROY) suffix에서 파생하며, terraform이 아닌
     * operation(CONDITION_CHECK)이나 규약을 벗어난 이름은 empty다. 이 값은 표시용 분류 라벨일 뿐이고,
     * 폴 정규화·API 바인딩의 authority는 여전히 TerraformBindingCatalog의 행(operation → TerraformJobType)이다.
     */
    public Optional<String> terraformAction() {
        if (!Mechanism.TERRAFORM_JOB.equals(mechanism)) {
            return Optional.empty();
        }
        int marker = name().lastIndexOf("_TF_");
        return marker < 0 ? Optional.empty() : Optional.of(name().substring(marker + "_TF_".length()));
    }

    /**
     * 이 operation이 terraform Plan을 던지는지. 분기가 {@link #terraformAction()}이 돌려주는 표시 문자열을
     * 직접 비교하지 않게 하려고 둔다 — 그 문자열은 이름 접미사에서 파생되므로, 비교로 분기하면 접미사
     * 규칙이 바뀔 때 컴파일도 테스트도 아무 말 없이 분기만 조용히 어긋난다.
     */
    public boolean isTerraformPlan() {
        return terraformAction().filter(TerraformJobType.PLAN.name()::equals).isPresent();
    }

    /**
     * terraform slot 소비 여부의 단일 authority다. slot 소비는 operation이 아니라 mechanism의 속성이라, 값을 op마다
     * 두지 않고 mechanism으로 판별한다. insert 때 이 값이 task 행(consumes_terraform_slot)에 캐시돼 slot 게이트가 쓴다.
     */
    public boolean consumesTerraformSlot() {
        return Mechanism.TERRAFORM_JOB.equals(mechanism);
    }

    /**
     * 이 operation이 승인 게이트인가. 게이트는 여러 경계에서 일반 task와 다르게 다뤄지므로(custom recipe 배치
     * 금지, task catalog 노출 제외, 재시작 지점 당김) 판별을 mechanism 비교 리터럴로 흩지 않고 여기 모은다.
     */
    public boolean isApprovalGate() {
        return Mechanism.APPROVAL.equals(mechanism);
    }

    /**
     * 저장된 operation 이름(String)을 상수로 해석한다. 미해석(카탈로그에서 제거/rename된 옛 값)은 예외 대신
     * empty를 돌려주어 read가 터지지 않게 한다 — TaskDefinition.find와 같은 열화 규약이며,
     * TaskOperationConverter가 이 해석으로 행을 읽는다.
     */
    public static Optional<TaskOperation> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TaskOperation.valueOf(name));
        } catch (IllegalArgumentException notAnOperation) {
            return Optional.empty();
        }
    }
}
