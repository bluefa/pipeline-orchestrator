package com.bff.pipeline.service.notify;

import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.enums.PipelineStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Slack Incoming Webhook으로 종단 알림을 전달하는 sink다(ADR-022, 구현 명세 §4.3). V1 단일 sink라
 * 인터페이스 추상화 없이 구체 클래스다(YAGNI — 다중 sink가 필요해지면 그때 추출한다). HTTP 호출 상한은
 * {@code notifyRestClient} 빈의 connect/read 타임아웃({@code pipeline.notify.call-timeout})이 소유하므로
 * 별도 스레드풀이 없다. 비2xx/타임아웃/IO 실패는 {@code RestClientException}으로 던져지고, 호출자
 * (NotifyScheduler·admin test 엔드포인트)가 잡아 backoff 또는 probe 결과로 처리한다.
 *
 * 메시지 형식(간단한 텍스트 + attachment): DONE → :white_check_mark:/good, FAILED → :x:/danger
 * (+failed_task/error_code 필드), CANCELLED → :no_entry:/warning. pipeline_id는 항상 본문에 포함한다 —
 * at-least-once라 드물게 생기는 중복 메시지를 사람이 식별하는 키다. target_ref는 opaque 참조만 싣는다
 * (raw host/account/DB명 금지, ADR-022 §4 PII 하드 계약 — payload 구성 단계에서 강제된다).
 */
@Component
public class SlackNotifier {

    private static final String TEST_MESSAGE = ":bell: PII 파이프라인 알림 채널 테스트 메시지";

    private final RestClient notifyRestClient;

    public SlackNotifier(@Qualifier("notifyRestClient") RestClient notifyRestClient) {
        this.notifyRestClient = notifyRestClient;
    }

    /** 종단 알림 전달. 실패(비2xx/타임아웃/IO)면 예외 → 호출자(NotifyScheduler)가 잡아 backoff 기록. */
    public void deliver(String webhookUrl, NotifyPayload payload) {
        post(webhookUrl, toSlackMessage(payload));
    }

    /** admin 테스트 전송 — 실제 pipeline 없이 고정 메시지. */
    public void deliverTest(String webhookUrl) {
        post(webhookUrl, Map.of("text", TEST_MESSAGE));
    }

    private void post(String webhookUrl, Object message) {
        notifyRestClient.post().uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve().toBodilessEntity();   // 비2xx → RestClientException
    }

    /** payload → Slack 메시지 Map. package-private — 테스트가 형식(이모지/색/필드)을 직접 단언한다. */
    static Map<String, Object> toSlackMessage(NotifyPayload payload) {
        MessageStyle style = MessageStyle.forTerminalStatus(payload.terminalStatus());
        List<Map<String, Object>> fields = new ArrayList<>();
        addFieldUnlessNull(fields, "type", payload.type(), true);
        addFieldUnlessNull(fields, "status", payload.terminalStatus(), true);
        addFieldUnlessNull(fields, "target_ref", payload.targetRef(), false);
        addFieldUnlessNull(fields, "failed_task", payload.failedTask(), true);
        addFieldUnlessNull(fields, "error_code", payload.errorCode(), true);
        return Map.of(
                "text", headline(style, payload),
                "attachments", List.of(Map.of("color", style.color(), "fields", fields)));
    }

    /** 예: ":white_check_mark: *Pipeline DONE* — INSTALL (id 1234)". type이 null(열화)이면 그 구간을 뺀다. */
    private static String headline(MessageStyle style, NotifyPayload payload) {
        String typeSegment = payload.type() == null ? "" : " — " + payload.type();
        return style.emoji() + " *Pipeline " + payload.terminalStatus() + "*" + typeSegment
                + " (id " + payload.pipelineId() + ")";
    }

    private static void addFieldUnlessNull(List<Map<String, Object>> fields, String title, String value,
            boolean shortField) {
        if (value != null) {
            fields.add(Map.of("title", title, "value", value, "short", shortField));
        }
    }

    /** 종단 상태별 표시 스타일. claim 술어가 종단만 집으므로 비종단이 오면 계약 위반 — fail-fast. */
    private record MessageStyle(String emoji, String color) {
        static MessageStyle forTerminalStatus(String terminalStatus) {
            return switch (PipelineStatus.valueOf(terminalStatus)) {
                case DONE -> new MessageStyle(":white_check_mark:", "good");
                case FAILED -> new MessageStyle(":x:", "danger");
                case CANCELLED -> new MessageStyle(":no_entry:", "warning");
                case PENDING, RUNNING -> throw new IllegalStateException(
                        "a non-terminal status cannot be notified: " + terminalStatus);
            };
        }
    }
}
