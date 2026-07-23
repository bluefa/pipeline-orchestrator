package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * TaskDefinition 카탈로그 목록 항목이다(LIN-27, {@code GET /api/v1/task-definitions}). Custom Recipe 빌더가
 * "이 provider가 수행할 수 있는 Task 목록"을 렌더링하는 데 쓰는 얇은 뷰로, 실행 계약 API 상세는 담지 않는다
 * (상세는 {@link TaskDefinitionView}). {@code kind}는 실행 메커니즘(TERRAFORM_JOB/CONDITION_CHECK)이고,
 * {@code terraformAction}은 operation에서 파생한 표시용 액션(PLAN/APPLY/DESTROY)이며 terraform이 아니면 null이다.
 * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record TaskCatalogEntry(
        @JsonProperty("name") String name,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("provider") CloudProvider provider,
        @JsonProperty("kind") String kind,
        @JsonProperty("terraform_action") String terraformAction,
        @JsonProperty("consumes_terraform_slot") boolean consumesTerraformSlot) {

    public static TaskCatalogEntry from(TaskDefinition definition) {
        return TaskCatalogEntry.builder()
                .name(definition.name())
                .displayName(definition.displayName())
                .description(definition.description())
                .provider(definition.provider())
                .kind(definition.mechanism())
                .terraformAction(definition.operation().terraformAction().orElse(null))
                .consumesTerraformSlot(definition.consumesTerraformSlot())
                .build();
    }
}
