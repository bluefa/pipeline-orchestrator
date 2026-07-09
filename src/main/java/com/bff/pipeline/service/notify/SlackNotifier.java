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
import org.springframework.web.client.RestClientException;

/**
 * 끝난 파이프라인의 알림을 Slack Incoming Webhook으로 전송하는 클래스다.
 * V1에서 전송처는 Slack 하나뿐이라 인터페이스 없이 구체 클래스로 둔다 —
 * 전송처가 늘어나면 그때 인터페이스를 뽑는다.
 * HTTP 호출 시간은 {@code notifyRestClient} 빈의 connect/read 타임아웃
 * ({@code pipeline.notify.call-timeout})이 제한하므로 별도 스레드풀을 두지 않는다.
 * 2xx가 아닌 응답, 타임아웃, IO 실패는 {@code RestClientException}으로 던진다.
 * 호출자({@code NotifyScheduler})가 이를 잡아 간격을 늘려 가며 재시도한다.
 *
 * 메시지 형식은 간단한 텍스트 + attachment다.
 * DONE은 :white_check_mark: 이모지와 good 색, FAILED는 :x:와 danger 색(실패 단계와 에러 코드 필드 추가),
 * CANCELLED는 :no_entry:와 warning 색이다.
 * pipeline_id는 항상 본문에 넣는다 — 최소 한 번은 전달한다는 방침이라 드물게 같은 알림이
 * 두 번 갈 수 있는데, 그때 사람이 중복임을 알아보는 열쇠가 pipeline_id다.
 * target_ref에는 아무것도 드러내지 않는 참조 값만 싣는다.
 * raw host·계정·DB 이름은 금지다 — 이 규칙은 payload를 만드는 단계에서 강제된다.
 */
@Component
public class SlackNotifier {

    private final RestClient notifyRestClient;

    public SlackNotifier(@Qualifier("notifyRestClient") RestClient notifyRestClient) {
        this.notifyRestClient = notifyRestClient;
    }

    /** 알림 한 건을 전송한다. 실패(비2xx 응답/타임아웃/IO)하면 예외를 던지고, 호출자(NotifyScheduler)가 잡아 재시도 정보를 기록한다. */
    public void deliver(String webhookUrl, NotifyPayload payload) {
        post(webhookUrl, toSlackMessage(payload));
    }

    private void post(String webhookUrl, Object message) {
        notifyRestClient.post().uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                // 성공으로 인정하는 응답은 2xx뿐이다. RestClient 기본 동작은 4xx/5xx만 예외로 만들기 때문에,
                // 그대로 두면 3xx가 성공으로 통과한다 — 그러면 notified_at이 잘못 찍혀 알림이 조용히 사라진다.
                // 그래서 2xx도 에러도 아닌 나머지 상태를 여기서 명시적으로 실패로 판정한다.
                // 4xx/5xx는 기본 RestClientResponseException 경로를 그대로 둬서 로그의 resp_class 구분을 살린다.
                .onStatus(status -> !status.is2xxSuccessful() && !status.isError(),
                        (request, response) -> {
                            throw new RestClientException(
                                    "slack webhook answered non-2xx: " + response.getStatusCode());
                        })
                .toBodilessEntity();   // 2xx가 아니면 RestClientException이 난다
    }

    /** payload를 Slack 메시지 Map으로 바꾼다. package-private인 이유: 테스트가 형식(이모지/색/필드)을 직접 단언한다. */
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

    /** 예: ":white_check_mark: *Pipeline DONE* — INSTALL (id 1234)". type을 알 수 없는 옛 데이터면(null) 그 구간을 뺀다. */
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

    /** 끝난 상태별 이모지와 색이다. 점유 조회가 끝난 파이프라인만 집어오므로, 끝나지 않은 상태가 여기 오면 계약 위반이다 — 즉시 실패시킨다. */
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
