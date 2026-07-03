package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * preview recipe의 한 단계다(P9). kind는 실행 메커니즘(TERRAFORM_JOB/CONDITION_CHECK)이고,
 * taskDefinition은 이 단계를 만든 TaskDefinition 상수 이름이다. definition은 그 항목의 실행 계약 뷰
 * (호출 API·성공 판정 정책·result 저장 방식)로, 운영자가 생성 전에 각 단계가 정확히 무엇을 하는지 볼 수 있게 한다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
public record RecipePreviewStep(
        @JsonProperty("sequence") int sequence,
        @JsonProperty("task_definition") String taskDefinition,
        @JsonProperty("kind") String kind,
        @JsonProperty("operation") TaskOperation operation,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("consumes_terraform_slot") boolean consumesTerraformSlot,
        @JsonProperty("definition") TaskDefinitionView definition) {

    public static RecipePreviewStep from(int sequence, TaskDefinition definition) {
        return new RecipePreviewStep(sequence, definition.name(), definition.mechanism(),
                definition.operation(), definition.displayName(), definition.consumesTerraformSlot(),
                TaskDefinitionView.from(definition));
    }
}
