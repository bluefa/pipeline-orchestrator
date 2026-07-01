package com.bff.pipeline.exception;

/**
 * REST 오류 응답에 실리는 안정적인 오류 코드다. 와이어 값은 {@code ORCHESTRATION_} 접두어 + 상수 이름이다
 * (예: {@link #PIPELINE_NOT_FOUND} → {@code ORCHESTRATION_PIPELINE_NOT_FOUND}). 코드를 여기 한곳에 모아
 * magic literal을 없앤다.
 */
public enum OrchestrationErrorCode {

    PIPELINE_ID_REQUIRED,
    PIPELINE_TYPE_REQUIRED,
    TARGET_REQUIRED,
    PIPELINE_NOT_FOUND,
    TASK_NOT_FOUND,
    PIPELINE_ALREADY_ACTIVE,
    PIPELINE_PERSISTENCE_ERROR,
    UNSUPPORTED_RECIPE,
    PROVIDER_LOOKUP_FAILED,
    INVALID_STATISTICS_PERIOD,
    INVALID_PARAMETER,
    INTERNAL_ERROR;

    private static final String PREFIX = "ORCHESTRATION_";

    public String code() {
        return PREFIX + name();
    }
}
