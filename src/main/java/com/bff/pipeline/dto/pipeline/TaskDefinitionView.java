package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.TaskDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TaskDefinition 카탈로그 항목의 Admin 노출 뷰다 — 이름·표시명·설명에 더해 실행 계약(TaskExecutionSpec:
 * 실제 호출 API, 성공 판정 정책, result 저장 방식)을 그대로 담아 운영자가 task가 정확히 무엇을 하는지 알 수 있게 한다.
 * timeout·폴링 간격·재시도 횟수 값은 여기 없다 — TaskDetail의 effective_* 필드가 실제 적용값이고, 정책 텍스트가
 * 그 필드 이름을 언급해 잇는다. dispatch_api/result_api는 CONDITION_CHECK 항목에서 null이라 직렬화에서 뺀다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDefinitionView(
        @JsonProperty("name") String name,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("dispatch_api") String dispatchApi,
        @JsonProperty("status_api") String statusApi,
        @JsonProperty("result_api") String resultApi,
        @JsonProperty("success_policy") String successPolicy,
        @JsonProperty("result_storage") String resultStorage) {

    public static TaskDefinitionView from(TaskDefinition definition) {
        return new TaskDefinitionView(definition.name(), definition.displayName(), definition.description(),
                definition.spec().dispatchApi(), definition.spec().statusApi(), definition.spec().resultApi(),
                definition.spec().successPolicy(), definition.spec().resultStorage());
    }
}
