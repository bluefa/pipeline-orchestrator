package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 재시작 파이프라인 상세({@link PipelineDetail})에 얹는 원본 실행 요약 블록이다(재시작 설계 §3.3).
 * 재시작 실행의 done/total은 자기 suffix 기준을 유지하고, "원본 8단계 중 6단계부터"는 이 블록으로 그린다.
 * {@code resumedFromSequence}는 저장값이 아니라 파생값이다 — 재시작 체인의 첫 task가 가리키는
 * (origin_task_id) 원본 task의 sequence이며, 원본 행이 사라졌으면 null이다(FK 없음 — null-safe 표시가 계약).
 * 인접 동형 인자가 있어 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record RestartOriginView(
        @JsonProperty("pipeline_id") long pipelineId,
        @JsonProperty("type") PipelineType type,
        @JsonProperty("recipe_definition") String recipeDefinition,
        @JsonProperty("status") PipelineStatus status,
        @JsonProperty("total_task_count") long totalTaskCount,
        @JsonProperty("done_task_count") long doneTaskCount,
        @JsonProperty("resumed_from_sequence") Integer resumedFromSequence) {
}
