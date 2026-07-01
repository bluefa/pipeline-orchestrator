package com.bff.pipeline.enums;

/**
 * 파이프라인이 대상 인프라에 수행하는 작업 유형이다. {@code INSTALL}은 구축, {@code DELETE}는 철거를 뜻한다.
 * 이 값이 태스크 체인 레시피({@code RecipeCatalog})를 결정하며, 각 파이프라인 행(row)에 저장된다.
 */
public enum PipelineType {
    /** 대상 인프라를 구축하는 파이프라인 유형. */
    INSTALL,
    /** 대상 인프라를 철거하는 파이프라인 유형. */
    DELETE
}
