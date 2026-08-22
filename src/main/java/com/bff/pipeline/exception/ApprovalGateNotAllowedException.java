package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * custom recipe 요청에 승인 게이트 task를 넣었다. 승인 게이트는 카탈로그 레시피가 Plan 바로 뒤에 배치할
 * 때에만 의미가 있다 — 앞에 볼 Plan이 없는 자리에 놓이면 승인자가 근거 없이 버튼만 보게 되므로, 요청이
 * 임의로 배치하는 것을 400으로 막는다(승인 게이트 ADR Phase 1 범위).
 */
public class ApprovalGateNotAllowedException extends OrchestrationException {

    public ApprovalGateNotAllowedException(String taskName) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.APPROVAL_GATE_NOT_ALLOWED,
                "task " + taskName + " is an approval gate and cannot be placed in a custom recipe");
    }
}
