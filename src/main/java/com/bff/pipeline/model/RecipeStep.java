package com.bff.pipeline.model;

import com.bff.pipeline.enums.TaskOperation;
import java.util.Objects;

/**
 * recipe의 한 단계 — 어떤 task type(taskName)이 어떤 operation을 수행하는지를 가리키는 값.
 * taskName은 {@code TaskTypeRegistry}가 해당 {@code TaskType} 구현체를 찾는 데 사용하는 안정적인 식별자이고,
 * operation은 그 task type이 실행할 구체적인 작업을 지정한다.
 */
public record RecipeStep(String taskName, TaskOperation operation) {
    public RecipeStep {
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("taskName must not be blank");
        }
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
