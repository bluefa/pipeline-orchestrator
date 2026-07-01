package com.bff.pipeline.enums;

import java.util.Optional;

/**
 * task의 명명된 1급 정체성 카탈로그다(설계: docs/task-catalog-extension-plan.md §1). 느슨한
 * (taskName, operation) 쌍 대신, 각 항목이 "이 task가 정확히 무슨 역할인지"를 이름으로 못박고 metadata를 실어
 * Admin API로 노출된다. 동작(mechanism)과 정체성(이 enum)을 분리한다 — 동작은 소수의 TaskType 구현이 담당하고,
 * 이 enum은 그중 하나를 mechanism(=taskName)으로 가리킬 뿐이다. 그래서 provider·operation 변형이 늘어도
 * 동작 클래스가 아니라 이 카탈로그 항목만 는다.
 *
 * 버전 불변 규약: 상수 이름에 버전(_V1)을 박아 의미를 불변으로 둔다 — V1의 mechanism/operation은 절대 바꾸지 않고,
 * 바뀌면 _V2를 추가한다. Task 행은 이 상수 이름(String)을 진실원으로 저장하므로, 배포 사이에 정의가 바뀌어 in-flight
 * 행의 실행 의미가 달라지는 일이 없다. find는 미해석(삭제/rename된 옛 이름)을 예외가 아니라 Optional.empty()로 돌려주어,
 * 엔진이 ErrorCode.UNKNOWN_TASK로 깨끗이 열화하게 한다.
 *
 * mechanism은 String 리터럴로 둔다(TaskType.NAME 상수와 값이 같아야 한다). enum이 service 계층에 의존하지 않도록
 * 리터럴로 두되, 부팅 시 TaskTypeRegistry가 모든 mechanism이 실제 등록된 TaskType을 가리키는지 검증해 리터럴
 * 드리프트를 fail-fast로 잡는다.
 */
public enum TaskDefinition {

    APPLY_NETWORK_V1(Mechanism.TERRAFORM_JOB, TaskOperation.APPLY_NETWORK, true,
            "네트워크 생성", "대상의 네트워크 인프라를 Terraform으로 구성(apply)한다."),
    NETWORK_READY_V1(Mechanism.CONDITION_CHECK, TaskOperation.NETWORK_READY, false,
            "네트워크 준비 확인", "네트워크가 준비 완료 상태가 될 때까지 조건을 확인한다."),
    DESTROY_NETWORK_V1(Mechanism.TERRAFORM_JOB, TaskOperation.DESTROY_NETWORK, true,
            "네트워크 철거", "대상의 네트워크 인프라를 Terraform으로 철거(destroy)한다.");

    /** mechanism 이름 리터럴 — 값은 각 {@code TaskType.NAME}과 일치해야 하며 부팅 시 검증된다. */
    public static final class Mechanism {
        public static final String TERRAFORM_JOB = "TERRAFORM_JOB";
        public static final String CONDITION_CHECK = "CONDITION_CHECK";

        private Mechanism() {
        }
    }

    private final String mechanism;
    private final TaskOperation operation;
    private final boolean consumesTerraformSlot;
    private final String displayName;
    private final String description;

    TaskDefinition(String mechanism, TaskOperation operation, boolean consumesTerraformSlot,
            String displayName, String description) {
        this.mechanism = mechanism;
        this.operation = operation;
        this.consumesTerraformSlot = consumesTerraformSlot;
        this.displayName = displayName;
        this.description = description;
    }

    /** 이 정의를 실행할 {@code TaskType}의 이름(=taskName). */
    public String mechanism() {
        return mechanism;
    }

    public TaskOperation operation() {
        return operation;
    }

    /**
     * 이 정의가 terraform slot을 소비하는지 여부다. mechanism의 {@code TaskType.consumesTerraformSlot()}에서 오는
     * 값을 정의에 캐시해 insert 시 registry 조회 없이 행에 박게 한다. 부팅 때 registry가 실제 TaskType과 일치하는지
     * 검증하므로 드리프트는 fail-fast로 잡힌다.
     */
    public boolean consumesTerraformSlot() {
        return consumesTerraformSlot;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
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
