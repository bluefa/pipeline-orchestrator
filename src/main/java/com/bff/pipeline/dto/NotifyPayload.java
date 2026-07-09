package com.bff.pipeline.dto;

import lombok.Builder;

/**
 * 알림에 실어 보내는 내용이다. 민감 정보 유출을 막기 위해 허용된 필드만 담는다.
 * {@code targetRef}에는 대상의 안전한 참조 값(target 키/id)만 넣는다.
 * raw hostname·계정·DB 이름 같은 민감한 연결 식별자는 절대 직렬화하지 않는다.
 * {@code failedTask}에는 정해진 목록 안의 recipe 단계 이름만, {@code errorCode}에는 승인된
 * 에러 코드 이름만 넣는다 — 둘 다 FAILED일 때만 채워지고 아니면 null이다.
 * raw 예외 메시지나 URL을 담을 필드는 스키마에 아예 없다.
 * {@code type}은 지금의 enum이 해석하지 못하는 옛 값이면 null로 읽힐 수 있다(INSTALL | DELETE | CUSTOM | null).
 */
@Builder
public record NotifyPayload(
        long pipelineId,
        String type,
        String terminalStatus,
        String targetRef,
        String failedTask,
        String errorCode,
        String schemaVersion) {

    /** payload 스키마 버전. 알림을 받는 쪽이 형식이 바뀌었는지 구분하는 기준 값이다. */
    public static final String SCHEMA_VERSION = "1";
}
