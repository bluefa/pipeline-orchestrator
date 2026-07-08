package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 알림 채널 테스트 전송 결과다(POST /api/v1/admin/notification-channel/test, ADR-022 §6.1). 전달 실패는
 * probe 결과이지 서버 오류가 아니므로 이 본문으로 항상 200이 내려간다 — {@code delivered}가 성공 여부,
 * {@code error}가 실패 사유(성공 시 null)다.
 */
public record TestResult(
        @JsonProperty("delivered") boolean delivered,
        @JsonProperty("error") String error) {
}
