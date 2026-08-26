package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 승인 기한이 지난 뒤에 결정하려 했다. 아직 만료 처리가 기록되기 전이라 요청은 "대기 중"으로 보이지만,
 * 기한이 지난 승인은 통과시키지 않는다 — "이미 처리됨"과는 다른 상황이라 별도로 알린다
 * (승인 게이트 ADR §결정 4). 다시 받으려면 파이프라인을 재시작해야 한다.
 */
public class ApprovalDeadlinePassedException extends OrchestrationException {

    public ApprovalDeadlinePassedException(Long taskId) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.APPROVAL_DEADLINE_PASSED,
                "the approval deadline for task " + taskId + " has passed");
    }
}
