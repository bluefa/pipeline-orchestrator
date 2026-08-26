package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 승인·반려 요청 본문이다(승인 게이트 ADR §결정 4). 승인자가 누구인지는 오케스트레이터가 판단하지 않는다 —
 * 관리자 권한 확인과 신원 확정은 BFF가 자기 세션에서 하고, 확정된 값을 여기 실어 보낸다. 이 계약은
 * 취소 API와 같은 신뢰 모델 위에 서 있다: 내부망에서 BFF만 이 API를 부른다.
 *
 * {@code approver_id}는 필수다(비면 400). 승인자를 모르는 결정은 기록해도 감사에 쓸 수 없기 때문이다.
 * {@code approver_name}은 화면 표시용이라 선택이다. 와이어 필드는 snake_case 계약을 따른다.
 */
public record ApprovalDecisionRequest(
        @JsonProperty("approver_id") String approverId,
        @JsonProperty("approver_name") String approverName) {
}
