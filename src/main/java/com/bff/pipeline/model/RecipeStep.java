package com.bff.pipeline.model;

import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import java.util.Objects;

/**
 * recipe의 한 단계 — 명명된 {@link TaskDefinition} 하나를 가리킨다(설계: {@code docs/task-catalog-extension-plan.md}).
 * mechanism(taskName)/operation은 정의에서 파생하므로 여기서 따로 들고 있지 않는다. 정의가 진실원이고, step은
 * 순서 있는 참조일 뿐이다.
 */
public record RecipeStep(TaskDefinition definition) {
    public RecipeStep {
        Objects.requireNonNull(definition, "definition must not be null");
    }

    /** 이 step을 실행할 {@code TaskType}의 이름. */
    public String taskName() {
        return definition.mechanism();
    }

    public TaskOperation operation() {
        return definition.operation();
    }
}
