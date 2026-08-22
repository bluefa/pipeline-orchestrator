package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 실시간 현황이다(P1). 사용 중 수치(runningPipelineCount, pendingPipelineCount, inProgressTerraformTaskCount,
 * activeClaimCount)와 설정된 상한(terraformSlotCap, runningPipelineCap)을 함께 실어 "N / M" 사용률을 UI가 그릴 수 있게 한다.
 * 상한은 ADR-021 ExecutionSettings의 소프트 캡이다("Worker 개수"가 아니라 동시 수행 slot 총량).
 * runningPipelineCount는 RUNNING만 세고, pendingPipelineCount는 시작 지연 대기(PENDING)를 따로 노출한다(LIN-30).
 * awaitApprovalPipelineCount는 사람의 승인을 기다리는 실행이다 — RUNNING도 PENDING도 아니고 claim도 쥐지 않아
 * 다른 어느 수치에도 잡히지 않으므로, 따로 세지 않으면 최대 24시간 멈춰 선 실행이 현황에서 통째로 사라진다.
 * "지금 내 결재를 기다리는 것이 몇 건인가"가 이 화면에서 가장 먼저 보여야 할 값이라 상한 없이 건수만 싣는다.
 * 인접 long 성분이 많아 positional 생성 시 인자 뒤바뀜이 컴파일에 안 잡히므로 {@code @Builder}로 짓는다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
@Builder
public record LivePipelineStatistics(
        @JsonProperty("running_pipeline_count") long runningPipelineCount,
        @JsonProperty("pending_pipeline_count") long pendingPipelineCount,
        @JsonProperty("await_approval_pipeline_count") long awaitApprovalPipelineCount,
        @JsonProperty("in_progress_terraform_task_count") long inProgressTerraformTaskCount,
        @JsonProperty("terraform_slot_cap") int terraformSlotCap,
        @JsonProperty("running_pipeline_cap") int runningPipelineCap,
        @JsonProperty("active_claim_count") long activeClaimCount) {
}
