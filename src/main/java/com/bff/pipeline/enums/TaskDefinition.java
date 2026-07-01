package com.bff.pipeline.enums;

import java.util.Optional;

/**
 * task의 명명된 1급 정체성 카탈로그다(설계: docs/task-catalog-extension-plan.md §1). 느슨한
 * (taskName, operation) 쌍 대신, 각 항목이 "이 task가 정확히 무슨 역할인지"를 이름으로 못박고 metadata를 실어
 * Admin API로 노출된다. 고유 축은 provider + operation + metadata뿐이다 — mechanism과 slot 소비 여부는 operation이
 * 유일하게 결정하므로(operation이 mechanism을 결정한다) operation에서 파생만 한다.
 *
 * 버전 불변 규약: 상수 이름에 버전(_V1)을 박아 의미를 불변으로 둔다 — V1의 provider/operation은 절대 바꾸지 않고,
 * 바뀌면 _V2를 추가한다. Task 행은 이 상수 이름(String)을 진실원으로 저장하므로, 배포 사이에 정의가 바뀌어 in-flight
 * 행의 실행 의미가 달라지는 일이 없다. find는 미해석(삭제/rename된 옛 이름)을 예외가 아니라 Optional.empty()로 돌려주어,
 * 엔진이 ErrorCode.UNKNOWN_TASK로 깨끗이 열화하게 한다.
 */
public enum TaskDefinition {

    APPLY_NETWORK_V1(CloudProvider.AWS, TaskOperation.APPLY_NETWORK,
            "네트워크 생성", "대상의 네트워크 인프라를 Terraform으로 구성(apply)한다."),
    NETWORK_READY_V1(CloudProvider.AWS, TaskOperation.NETWORK_READY,
            "네트워크 준비 확인", "네트워크가 준비 완료 상태가 될 때까지 조건을 확인한다."),
    DESTROY_NETWORK_V1(CloudProvider.AWS, TaskOperation.DESTROY_NETWORK,
            "네트워크 철거", "대상의 네트워크 인프라를 Terraform으로 철거(destroy)한다.");

    private final CloudProvider provider;
    private final TaskOperation operation;
    private final String displayName;
    private final String description;

    TaskDefinition(CloudProvider provider, TaskOperation operation, String displayName, String description) {
        this.provider = provider;
        this.operation = operation;
        this.displayName = displayName;
        this.description = description;
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
