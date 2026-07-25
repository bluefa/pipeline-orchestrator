package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 재시작 대상이 target의 최신 실행이 아니다 — stale한 과거 이력의 재시작은 현재 인프라 상태와 무관한 실행을
 * 만들므로 거절한다(재시작 설계 결정 5). 검사는 best-effort이고 최종 동시성 심판은 active_target 유니크
 * 제약이다. 409 Conflict + code {@code ORCHESTRATION_PIPELINE_NOT_LATEST}로 매핑된다.
 */
public class PipelineNotLatestException extends OrchestrationException {

    public PipelineNotLatestException(long pipelineId, long latestPipelineId) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_NOT_LATEST,
                "pipeline " + pipelineId + " is not the latest run (latest: " + latestPipelineId + ")");
    }
}
