package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 같은 target에 대한 생성이 동시성 경합으로 정해진 재시도 안에 수렴하지 못한 경우다. insert는 active-target
 * 유니크로 계속 튕기는데 복구 조회는 그 사이 대상이 풀려 매번 비는(활성↔해제 flapping) 드문 상황으로, 서버
 * 버그가 아니라 일시적 충돌이다. 409 Conflict + code {@code ORCHESTRATION_PIPELINE_TARGET_CONFLICT}로 매핑된다.
 */
public class PipelineCreationConflictException extends OrchestrationException {

    public PipelineCreationConflictException(String target, int attempts) {
        super(HttpStatus.CONFLICT, "ORCHESTRATION_PIPELINE_TARGET_CONFLICT",
                "could not create a pipeline for target '" + target + "' after " + attempts
                        + " attempts due to concurrent active-target contention");
    }
}
