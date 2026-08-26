package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.enums.ApprovalChannel;
import com.bff.pipeline.enums.ApprovalStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import lombok.Builder;

/**
 * 승인 요청 한 건의 읽기 전용 뷰다. 승인 게이트 태스크에만 붙고 나머지 태스크에서는 null이다. 승인 화면이
 * 필요한 것을 한 블록에 모은다 — 지금 어떤 상태인지, 언제까지 결정해야 하는지, 누가 어디서 결정했는지,
 * 그리고 무엇을 승인하는 것인지(plan 요약).
 *
 * {@code plan_summary}는 저장된 JSON을 다시 감싸지 않고 그대로 내보낸다 — 백엔드가 만든 요약을 문자열로
 * 한 번 더 포장하면 콘솔이 JSON 안의 JSON을 풀어야 한다. 요약이 없으면(아직 만들어지지 않았거나 원천이
 * 없었으면) null이다. 요약의 모양은 {@link PlanSummary}가 정의한다.
 *
 * 요청자·확인 요청 메시지는 여기 없다 — 그 둘은 태스크가 아니라 실행 전체의 속성이라 파이프라인 상세에
 * 담긴다. 같은 값을 두 곳에 복사하지 않는다.
 */
@Builder
public record TaskApprovalView(
        @JsonProperty("status") ApprovalStatus status,
        @JsonProperty("requested_at") Instant requestedAt,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("decided_at") Instant decidedAt,
        @JsonProperty("approver_id") String approverId,
        @JsonProperty("approver_name") String approverName,
        @JsonProperty("channel") ApprovalChannel channel,
        @JsonProperty("plan_summary") @JsonRawValue String planSummary) {

    public static TaskApprovalView from(TaskApproval approval) {
        return TaskApprovalView.builder()
                .status(approval.getStatus())
                .requestedAt(approval.getRequestedAt())
                .expiresAt(approval.getExpiresAt())
                .decidedAt(approval.getDecidedAt())
                .approverId(approval.getApproverId())
                .approverName(approval.getApproverName())
                .channel(approval.getChannel())
                .planSummary(approval.getPlanSummary())
                .build();
    }
}
