package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 승인·반려를 요청했지만 그 태스크가 승인을 기다리는 상태가 아니다 — 승인 게이트가 아닌 일반 태스크이거나,
 * 아직 승인 요청이 만들어지지 않았다. 어느 쪽이든 결정할 대상이 없으므로 409로 거절한다.
 */
public class TaskNotAwaitingApprovalException extends OrchestrationException {

    public TaskNotAwaitingApprovalException(Long taskId) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.TASK_NOT_AWAITING_APPROVAL,
                "task " + taskId + " is not awaiting approval");
    }
}
