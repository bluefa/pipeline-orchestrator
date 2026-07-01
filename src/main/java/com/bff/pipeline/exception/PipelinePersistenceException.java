package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * pipeline 생성 중 active-target 유니크가 아닌 예상 밖 무결성 위반이 난 경우다(진짜 버그나 스키마 문제). 원인을
 * 그대로 보존한 채 500 Internal Server Error + code {@code ORCHESTRATION_PIPELINE_PERSISTENCE_ERROR}로 매핑한다 —
 * raw {@code DataIntegrityViolationException}이 컨트롤러까지 새어 나가지 않도록 제어된 예외로 감싼다.
 */
public class PipelinePersistenceException extends OrchestrationException {

    public PipelinePersistenceException(String target, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, OrchestrationErrorCode.PIPELINE_PERSISTENCE_ERROR,
                "failed to persist a pipeline for target '" + target + "'", cause);
    }
}
