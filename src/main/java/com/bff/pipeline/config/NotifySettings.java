package com.bff.pipeline.config;

import java.time.Instant;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 종단 알림 기능의 설정 모음이다. application.yml의 {@code pipeline.notify.*} 키에서 읽어오고,
 * 세 값 모두 환경변수로 주입한다. 폴링 주기나 재시도 횟수 같은 나머지 동작 값은 운영에서 바꿀 일이
 * 없어 설정으로 빼지 않고 {@code TerminalNotifier}의 상수로 뒀다.
 *
 * 각 설정의 의미:
 * - {@code enabled}: 알림 기능 스위치. 끄면(기본값) 알림 스레드가 아예 돌지 않는다.
 *   부팅 때 한 번 읽으므로 켜고 끄는 변경은 재시작해야 반영된다.
 * - {@code slackWebhookUrl}: 알림을 보낼 Slack Incoming Webhook 주소. 환경변수
 *   {@code PIPELINE_NOTIFY_SLACK_WEBHOOK_URL}로 주입하는 비밀값이라 로그나 API 응답에 원문을 남기지 않는다.
 * - {@code enabledAfter}: 이 시각 이후에 끝난 파이프라인만 알린다는 기준 시각. 알림 기능을 처음 켜는 순간
 *   과거에 끝난 파이프라인 전부가 한꺼번에 Slack으로 쏟아지는 것을 막는다. 이 값을 지우거나 과거로 옮기면
 *   옛 파이프라인들이 다시 알림 대상이 되므로, 한 번 정했으면 계속 유지해야 한다.
 *
 * 알림을 켜는(enabled=true) 배포는 slackWebhookUrl과 enabledAfter를 반드시 함께 줘야 하고,
 * 빠뜨리면 서버가 뜨는 시점에 어느 키가 문제인지 메시지에 담아 바로 실패시킨다.
 * 꺼진 배포는 둘 다 생략할 수 있다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline.notify")
public record NotifySettings(boolean enabled, String slackWebhookUrl, Instant enabledAfter) {

    public NotifySettings {
        if (enabled) {
            if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
                throw new IllegalArgumentException("pipeline.notify.slack-webhook-url must be set when "
                        + "pipeline.notify.enabled is true (inject the secret via environment variable)");
            }
            if (enabledAfter == null) {
                throw new IllegalArgumentException("pipeline.notify.enabled-after must be set when "
                        + "pipeline.notify.enabled is true (pipelines finished before it are never notified)");
            }
        }
    }
}
