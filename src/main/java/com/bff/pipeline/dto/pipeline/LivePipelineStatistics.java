package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 실시간 현황이다(P1). 사용 중 수치(runningPipelineCount, pendingPipelineCount, inProgressTerraformTaskCount,
 * activeClaimCount)와 설정된 상한(terraformSlotCap, runningPipelineCap)을 함께 실어 "N / M" 사용률을 UI가 그릴 수 있게 한다.
 * 상한은 ADR-021 ExecutionSettings의 소프트 캡이다("Worker 개수"가 아니라 동시 수행 slot 총량).
 * runningPipelineCount는 RUNNING만 세고, pendingPipelineCount는 시작 지연 대기(PENDING)를 따로 노출한다(LIN-30).
 * 인접 long 성분이 많아 positional 생성 시 인자 뒤바뀜이 컴파일에 안 잡히므로 {@code @Builder}로 짓는다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
@Builder
public record LivePipelineStatistics(
        @JsonProperty("running_pipeline_count") long runningPipelineCount,
        @JsonProperty("pending_pipeline_count") long pendingPipelineCount,
        @JsonProperty("in_progress_terraform_task_count") long inProgressTerraformTaskCount,
        @JsonProperty("terraform_slot_cap") int terraformSlotCap,
        @JsonProperty("running_pipeline_cap") int runningPipelineCap,
        @JsonProperty("active_claim_count") long activeClaimCount) {
}
