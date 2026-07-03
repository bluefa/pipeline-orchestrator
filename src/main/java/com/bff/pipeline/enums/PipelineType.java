package com.bff.pipeline.enums;

import java.util.Optional;

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
    CUSTOM;

    /**
     * 저장된 type 이름(String)을 상수로 해석한다. 미해석(추가/제거된 옛 값)은 예외 대신 empty를 돌려주어
     * read가 터지지 않게 한다 — TaskOperation.find와 같은 열화 규약이며, PipelineTypeConverter가 이 해석으로
     * 행을 읽는다.
     */
    public static Optional<PipelineType> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PipelineType.valueOf(name));
        } catch (IllegalArgumentException notAType) {
            return Optional.empty();
        }
    }
}
