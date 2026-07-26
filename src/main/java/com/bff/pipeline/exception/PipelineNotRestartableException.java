package com.bff.pipeline.exception;

import com.bff.pipeline.enums.PipelineStatus;
import org.springframework.http.HttpStatus;

/**
 * 재시작 허용표(재시작 설계 결정 5) 위반이다 — 재시작은 최신 FAILED/CANCELLED 실행에서만 가능하다.
 * DONE은 재시작할 실패 지점이 없고(active_target 백스톱도 없어 이 검사가 유일 방어선), RUNNING/PENDING은
 * 재시작이 아니라 취소 대상이다. 409 Conflict + code {@code ORCHESTRATION_PIPELINE_NOT_RESTARTABLE}로 매핑된다.
 */
public class PipelineNotRestartableException extends OrchestrationException {

    public PipelineNotRestartableException(long pipelineId, PipelineStatus status) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_NOT_RESTARTABLE,
                "pipeline " + pipelineId + " is " + status
                        + " — only the latest FAILED/CANCELLED run can be restarted");
    }
}
