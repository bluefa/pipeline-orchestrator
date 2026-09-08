package com.bff.pipeline.enums;

import java.util.Optional;

/**
 * 파이프라인 한 번이 수행하는 업무 유형이다. 인프라 설치·삭제, 확정정보 재입력, 연결 테스트를 구분해
 * 같은 대상에서도 원하는 업무의 진행 현황과 이력을 찾을 수 있게 한다. CUSTOM은 운영자가 Task 순서를
 * 직접 구성한 실행이다. 유형은 생성 시 저장하며, 값이 존재한다고 해당 Recipe가 실행 가능한 것은 아니다.
 * 실제 생성 가능 여부는 대상의 provider와 활성 Recipe 카탈로그가 결정한다.
 */
public enum PipelineType {
    /** 대상 인프라를 구축하는 파이프라인 유형. */
    INSTALL,
    /** 대상 인프라를 철거하는 파이프라인 유형. */
    DELETE,
    /** 기존 인프라와 확정정보를 정리하고 새 확정정보를 입력하는 실행 유형. */
    RECONFIRM,
    /** 연결 테스트의 실행과 결과 확인을 수행하는 유형. */
    TEST_CONNECTION,
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
