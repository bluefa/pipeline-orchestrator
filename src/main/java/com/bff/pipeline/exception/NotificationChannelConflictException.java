package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 동시에 들어온 최초 알림 채널 설정 요청이 singleton PK 경쟁에서 진 경우다. 409 Conflict +
 * code {@code ORCHESTRATION_NOTIFICATION_CHANNEL_CONFLICT}로 매핑된다 — 승자가 이미 행을 만들었으므로
 * 클라이언트는 그대로 재시도하면 된다(이번엔 update 경로). raw 중복 키 예외가 catch-all을 타고 500으로
 * 새지 않게 하는 서비스 경계 번역이다(AGENTS.md 규칙 4, {@code PipelineCreator}의 판별 idiom과 동형).
 */
public class NotificationChannelConflictException extends OrchestrationException {

    public NotificationChannelConflictException(Throwable cause) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.NOTIFICATION_CHANNEL_CONFLICT,
                "the notification channel is being configured concurrently; retry", cause);
    }
}
