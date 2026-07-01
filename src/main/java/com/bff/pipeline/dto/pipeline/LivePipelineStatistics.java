package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 실시간 현황이다(P1). 사용 중 수치(runningPipelineCount, inProgressTerraformTaskCount, activeClaimCount)와
 * 설정된 상한(terraformSlotCap, runningPipelineCap)을 함께 실어 "N / M" 사용률을 UI가 그릴 수 있게 한다.
 * 상한은 ADR-021 ExecutionSettings의 소프트 캡이다("Worker 개수"가 아니라 동시 수행 slot 총량).
 * 와이어 필드는 snake_case로 직렬화한다.
 */
public record LivePipelineStatistics(
        @JsonProperty("running_pipeline_count") long runningPipelineCount,
        @JsonProperty("in_progress_terraform_task_count") long inProgressTerraformTaskCount,
        @JsonProperty("terraform_slot_cap") int terraformSlotCap,
        @JsonProperty("running_pipeline_cap") int runningPipelineCap,
        @JsonProperty("active_claim_count") long activeClaimCount) {
}
