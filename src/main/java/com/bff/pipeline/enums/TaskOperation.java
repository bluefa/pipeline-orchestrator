package com.bff.pipeline.enums;

/**
 * 태스크가 수행하는 도메인 액션이다(ADR-016 §2). 각 operation은 자기를 실행할 mechanism(=TaskType 이름)을 소유한다 —
 * operation이 mechanism을 유일하게 결정하기 때문이다(mechanism이 다른데 operation이 같은 경우는 없다). 그래서 라우팅의
 * 근원은 operation이고, TaskDefinition은 operation에서 mechanism/slot을 파생만 한다.
 *
 * operation 자체는 데이터다 — 실제 dispatch/poll 동작은 TaskType 구현(TerraformTask/ConditionCheckTask)에 있고,
 * operation은 (a) 어느 mechanism으로 갈지 라우팅하고 (b) InfraManager 호출 때 구체 액션 값으로 전달된다.
 *
 * mechanism은 String이라 TaskType의 열린 집합을 유지한다(새 TaskType은 이름으로 자기등록, 중앙 enum 수정 없음).
 * 값은 각 TaskType.NAME과 일치해야 하며 부팅 시 TaskTypeRegistry가 검증한다. slot은 mechanism의 속성이라 여기 캐시하고
 * 부팅 때 실제 TaskType.consumesTerraformSlot()과 일치하는지 검증한다.
 */
public enum TaskOperation {

    // ── TERRAFORM_JOB mechanism ──
    /** 네트워크 인프라를 구성(apply)하는 액션. */
    APPLY_NETWORK(Mechanism.TERRAFORM_JOB),
    /** 네트워크 인프라를 철거(destroy)하는 액션. */
    DESTROY_NETWORK(Mechanism.TERRAFORM_JOB),

    // ── CONDITION_CHECK mechanism ──
    /** 네트워크가 준비됐는지 확인(condition check)하는 액션. */
    NETWORK_READY(Mechanism.CONDITION_CHECK);

    /** mechanism 이름 리터럴 — 값은 각 TaskType.NAME과 일치해야 하며 부팅 시 검증된다. */
    public static final class Mechanism {
        public static final String TERRAFORM_JOB = "TERRAFORM_JOB";
        public static final String CONDITION_CHECK = "CONDITION_CHECK";

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
     * terraform slot 소비 여부. slot 소비는 operation이 아니라 mechanism의 속성이라, 값을 op마다 두지 않고 mechanism으로
     * 판별한다. 부팅 때 TaskTypeRegistry가 실제 TaskType.consumesTerraformSlot()과 일치하는지 검증하므로, 이 판별이
     * 틀리면 기동이 실패한다.
     */
    public boolean consumesTerraformSlot() {
        return Mechanism.TERRAFORM_JOB.equals(mechanism);
    }
}
