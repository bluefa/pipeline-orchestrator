package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * 목록·이력·최근카드가 쓰는 pipeline 요약 행이다(P3/P7/P8). targetName은 이 저장소에 없어(다른 repo 담당)
 * 싣지 않고 targetSourceId와 cloudProvider만 준다. 진행 N/M은 task 집계값(doneTaskCount/totalTaskCount)이다.
 * 와이어 필드는 BFF swagger 계약에 맞춰 snake_case로 직렬화한다({@code @JsonProperty}).
 */
public record PipelineSummary(
        @JsonProperty("pipeline_id") long pipelineId,
        @JsonProperty("type") PipelineType type,
        @JsonProperty("target_source_id") String targetSourceId,
        @JsonProperty("cloud_provider") CloudProvider cloudProvider,
        @JsonProperty("recipe_definition") String recipeDefinition,
        @JsonProperty("status") PipelineStatus status,
        @JsonProperty("done_task_count") long doneTaskCount,
        @JsonProperty("total_task_count") long totalTaskCount,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("last_activity_at") Instant lastActivityAt) {

    public static PipelineSummary from(Pipeline pipeline, long doneTaskCount, long totalTaskCount) {
        return new PipelineSummary(pipeline.getId(), pipeline.getType(), pipeline.getTarget(),
                pipeline.getCloudProvider(), pipeline.getRecipeDefinition(), pipeline.getStatus(),
                doneTaskCount, totalTaskCount, pipeline.getCreatedAt(), pipeline.getLastActivityAt());
    }
}
