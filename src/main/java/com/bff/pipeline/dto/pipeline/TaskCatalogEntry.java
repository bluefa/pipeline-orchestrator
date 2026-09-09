package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskProviderScope;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * TaskDefinition 카탈로그 목록 항목이다(LIN-27, {@code GET /api/v1/task-definitions}). Custom Recipe 빌더가
 * "이 provider가 수행할 수 있는 Task 목록"을 렌더링하는 데 쓰는 얇은 뷰로, 실행 계약 API 상세는 담지 않는다
 * (상세는 {@link TaskDefinitionView}). {@code kind}는 Terraform·조건·HTTP의 실행 메커니즘이고,
 * {@code terraformAction}은 operation에서 파생한 표시용 액션(PLAN/APPLY/DESTROY)이며 terraform이 아니면 null이다.
 * 현재 등록된 모든 정의는 CUSTOM의 구성 요소로 허용되므로 customAllowed는 항상 true다.
 * 이는 CSP 적합성과 현재 실행 가용성을 통과했다는 뜻이 아니며, 두 조건은 providerScope와
 * executionAvailable 및 생성 검증으로 별도 판단한다. 정의별 CUSTOM 금지 정책은 현재 존재하지 않는다.
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
        @JsonProperty("consumes_terraform_slot") boolean consumesTerraformSlot,
        @JsonProperty("provider_scope") TaskProviderScope providerScope,
        @JsonProperty("custom_allowed") boolean customAllowed,
        @JsonProperty("execution_available") boolean executionAvailable) {

    public static TaskCatalogEntry from(TaskDefinition definition, boolean available) {
        return TaskCatalogEntry.builder()
                .name(definition.name())
                .displayName(definition.displayName())
                .description(definition.description())
                .provider(definition.provider())
                .kind(definition.mechanism())
                .terraformAction(definition.operation().terraformAction().orElse(null))
                .consumesTerraformSlot(definition.consumesTerraformSlot())
                .providerScope(definition.providerScope()).customAllowed(true).executionAvailable(available)
                .build();
    }
}
