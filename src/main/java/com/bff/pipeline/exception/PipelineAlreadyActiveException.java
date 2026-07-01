package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 같은 target에 이미 비종료 활성 실행이 있는데 새 생성(트리거)이 들어온 경우다. 관리자 트리거는 web admin
 * 페이지의 사람 콜이라 중복을 조용히 흡수하지 않고 409 Conflict + code {@code ORCHESTRATION_PIPELINE_ALREADY_ACTIVE}로
 * 거절한다(ADR-016 §4). 그 활성 실행이 끝난 뒤 다시 트리거하면 생성된다.
 */
public class PipelineAlreadyActiveException extends OrchestrationException {

    public PipelineAlreadyActiveException(String target) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_ALREADY_ACTIVE,
                "target '" + target + "' already has an active run");
    }
}
