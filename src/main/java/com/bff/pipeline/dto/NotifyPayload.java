package com.bff.pipeline.dto;

import lombok.Builder;

/**
 * 종단 상태 알림 payload다 — ADR-022 §4의 PII 하드 계약에 따라 허용 필드만 싣는다. {@code targetRef}는
 * 대상의 opaque 참조(target 키/id)여야 하며 raw hostname·account·DB명 등 민감 연결 식별자는 직렬화하지
 * 않는다(MUST NOT). {@code failedTask}는 닫힌 recipe task 키만, {@code errorCode}는 승인된 코드만 —
 * 둘 다 FAILED일 때만 채워지고 아니면 null이다. raw 예외 메시지·URL 필드는 스키마에 존재하지 않는다.
 * {@code type}은 write-once 캐시의 미해석 열화로 null일 수 있다(INSTALL | DELETE | CUSTOM | null).
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

    /** payload 스키마 버전 상수 — 소비자 계약(ADR-022 §4 (b)). */
    public static final String SCHEMA_VERSION = "1";
}
