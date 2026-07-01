package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * preview recipe의 한 단계다(P9). kind는 실행 메커니즘(TERRAFORM_JOB/CONDITION_CHECK)이고,
 * taskDefinition은 이 단계를 만든 TaskDefinition 상수 이름이다. 와이어 필드는 snake_case로 직렬화한다.
 */
public record RecipePreviewStep(
        @JsonProperty("sequence") int sequence,
        @JsonProperty("task_definition") String taskDefinition,
        @JsonProperty("kind") String kind,
        @JsonProperty("operation") TaskOperation operation,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("consumes_terraform_slot") boolean consumesTerraformSlot) {

    public static RecipePreviewStep from(int sequence, TaskDefinition definition) {
        return new RecipePreviewStep(sequence, definition.name(), definition.mechanism(),
                definition.operation(), definition.displayName(), definition.consumesTerraformSlot());
    }
}
