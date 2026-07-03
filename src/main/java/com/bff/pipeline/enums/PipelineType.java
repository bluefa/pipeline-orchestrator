package com.bff.pipeline.enums;

/**
 * 파이프라인이 대상 인프라에 수행하는 작업 유형이다. {@code INSTALL}은 구축, {@code DELETE}는 철거를 뜻하며,
 * 이 둘은 (provider, type)으로 {@code RecipeCatalog}의 고정 recipe를 결정한다. {@code CUSTOM}은 운영자가 요청에서
 * task 순서를 직접 구성한 실행으로, RecipeCatalog를 거치지 않는다(LIN-18) — 이 값 자체가 곧 "custom 분류"라
 * 조회 API가 고정 recipe 실행과 구분한다. 각 파이프라인 행(row)에 저장된다.
 */
public enum PipelineType {
    /** 대상 인프라를 구축하는 파이프라인 유형. */
    INSTALL,
    /** 대상 인프라를 철거하는 파이프라인 유형. */
    DELETE,
    /** 운영자가 task 순서를 직접 구성한 custom 실행 유형(RecipeCatalog 미사용, 비영속 recipe). */
    CUSTOM
}
