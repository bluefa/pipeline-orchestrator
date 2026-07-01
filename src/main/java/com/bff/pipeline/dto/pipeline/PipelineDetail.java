package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 파이프라인 상세다(P4/P6/P10 응답). 도메인 메타 + ADR-021 실행 좌표(nextDueAt/leased/cancelRequested/dueLagMillis)
 * + 현재·최종 task 파생값 + task 흐름 목록을 담는다. claimedBy fencing 토큰은 보안상 노출하지 않고 leased(boolean)로만
 * 표시한다. currentTaskSequence는 최저 순번의 READY/IN_PROGRESS task이며, 그런 task가 없으면(모두 종료) null이다.
 * dueLagMillis는 now 기준 지연(now - nextDueAt)을 ms로 준 값으로, 아직 due가 아니거나 종료 상태면 0으로 클램프한다.
 * 와이어 필드는 BFF swagger 계약에 맞춰 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신
 * {@code @Builder}로 만든다.
 */
@Builder
public record PipelineDetail(
        @JsonProperty("pipeline_id") long pipelineId,
        @JsonProperty("type") PipelineType type,
        @JsonProperty("target_source_id") String targetSourceId,
        @JsonProperty("cloud_provider") CloudProvider cloudProvider,
        @JsonProperty("recipe_definition") String recipeDefinition,
        @JsonProperty("status") PipelineStatus status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("last_activity_at") Instant lastActivityAt,
        @JsonProperty("next_due_at") Instant nextDueAt,
        @JsonProperty("leased") boolean leased,
        @JsonProperty("cancel_requested") boolean cancelRequested,
        @JsonProperty("due_lag_millis") long dueLagMillis,
        @JsonProperty("current_task_sequence") Integer currentTaskSequence,
        @JsonProperty("final_task_sequence") Integer finalTaskSequence,
        @JsonProperty("current_fail_count") Integer currentFailCount,
        @JsonProperty("current_max_fail_count") Integer currentMaxFailCount,
        @JsonProperty("done_task_count") long doneTaskCount,
        @JsonProperty("total_task_count") long totalTaskCount,
        @JsonProperty("tasks") List<TaskSummary> tasks) {
}
