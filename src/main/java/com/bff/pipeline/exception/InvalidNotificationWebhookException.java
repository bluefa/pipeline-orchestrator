package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 알림 채널 upsert 입력이 계약을 위반한 경우다 — webhook URL이 SSRF 검증(https + 정확한
 * hooks.slack.com host + userinfo 없음)을 통과하지 못했거나, 필수 필드 {@code enabled}가 생략됐다.
 * 400 Bad Request + code {@code ORCHESTRATION_INVALID_NOTIFICATION_WEBHOOK}으로 매핑된다.
 */
public class InvalidNotificationWebhookException extends OrchestrationException {

    public InvalidNotificationWebhookException(String message) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.INVALID_NOTIFICATION_WEBHOOK, message);
    }
}
