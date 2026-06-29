package com.bff.pipeline.enums;

/**
 * 파이프라인이 대상 인프라에 수행하는 작업 유형을 나타낸다. {@code INSTALL}은 대상을 구축하고,
 * {@code DELETE}는 철거한다. 이 값은 태스크 체인 레시피({@code Recipes})를 결정하며
 * 각 파이프라인 행(row)에 저장된다.
 */
public enum PipelineType {
    /** 대상 인프라를 구축하는 파이프라인 유형. */
    INSTALL,
    /** 대상 인프라를 철거하는 파이프라인 유형. */
    DELETE
}
