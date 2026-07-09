package com.bff.pipeline.service.notify;

import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.enums.PipelineStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 끝난 파이프라인의 알림을 Slack Incoming Webhook으로 전송하는 클래스다.
 * V1에서 전송처는 Slack 하나뿐이라 인터페이스 없이 구체 클래스로 둔다 —
 * 전송처가 늘어나면 그때 인터페이스를 뽑는다.
 * HTTP 호출 시간은 {@code notifyRestClient} 빈의 connect/read 타임아웃({@link #CALL_TIMEOUT})이
 * 제한하므로 별도 스레드풀을 두지 않는다. 이 상한은 전송을 DB 트랜잭션 안에서 하는
 * {@code TerminalNotifier} 설계의 전제이기도 하다 — 행 잠금을 쥐는 시간이 이 값을 넘지 못한다.
 * 2xx가 아닌 응답, 타임아웃, IO 실패는 {@code RestClientException}으로 던진다.
 * 호출자({@code TerminalNotifier})가 이를 잡아 간격을 늘려 가며 재시도한다.
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

    /** Slack HTTP 호출의 connect/read 제한 시간. {@code notifyRestClient} 빈(PipelineConfig)이 이 값으로 만들어진다. */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient notifyRestClient;

    public SlackNotifier(@Qualifier("notifyRestClient") RestClient notifyRestClient) {
        this.notifyRestClient = notifyRestClient;
    }

    /**
     * 알림 한 건을 전송한다. 실패(비2xx 응답/타임아웃/IO)하면 {@link RestClientException}을 던지고,
     * 호출자(TerminalNotifier)가 잡아 재시도 정보를 기록한다.
     *
     * 던지는 예외는 여기서 새로 만든 것만 나간다 — Spring이 만드는 원본 예외는 메시지에 요청 URL
     * 전체(= webhook 주소, 비밀값)를 담기 때문에, 원본을 그대로 흘리면 호출자의 실패 로그에 비밀이
     * 찍힌다. 그래서 원본에서 응답 분류(HTTP 상태 코드 또는 예외 종류)만 뽑아 새 예외에 싣고,
     * 원본은 원인(cause)으로도 잇지 않는다. 메시지 조립({@code toSlackMessage})은 이 차단 밖에서
     * 한다 — 조립 예외는 전달 실패가 아니라 버그라서 그대로 전파해 드러나게 둔다.
     */
    public void deliver(String webhookUrl, NotifyPayload payload) {
        Map<String, Object> message = toSlackMessage(payload);
        try {
            post(webhookUrl, message);
        } catch (RuntimeException deliveryFailure) {   // harness-allow: targeted-catch — 비밀값 차단 경계: post가 던지는 모든 예외는 메시지/원인에 webhook 주소를 담을 수 있어, 여기서 분류만 남기고 끊는다.
            throw new RestClientException("slack delivery failed: " + classify(deliveryFailure));
        }
    }

    /**
     * 실패 예외에서 로그에 실어도 안전한 응답 분류만 뽑는다. HTTP 응답이 있었으면 상태 코드,
     * 없었으면(타임아웃, 연결 실패 등) 예외 종류 이름이다. 예외 메시지는 어떤 경우에도 옮기지
     * 않는다 — webhook 주소가 들어 있을 수 있다.
     */
    private static String classify(RuntimeException deliveryFailure) {
        if (deliveryFailure instanceof RestClientResponseException httpError) {
            return "http " + httpError.getStatusCode().value();
        }
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(deliveryFailure);
        return rootCause == deliveryFailure
                ? deliveryFailure.getClass().getSimpleName()
                : deliveryFailure.getClass().getSimpleName() + "/" + rootCause.getClass().getSimpleName();
    }

    private void post(String webhookUrl, Object message) {
        notifyRestClient.post().uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                // 성공으로 인정하는 응답은 2xx뿐이다. RestClient 기본 동작은 4xx/5xx만 예외로 만들기 때문에,
                // 그대로 두면 3xx가 성공으로 통과한다 — 그러면 notified_at이 잘못 찍혀 알림이 조용히 사라진다.
                // 그래서 2xx도 에러도 아닌 나머지 상태를 여기서 명시적으로 실패로 판정한다.
                // 4xx/5xx는 기본 예외 경로를 그대로 둔다. 어느 쪽이든 상태 코드가 deliver의 응답 분류에 실린다.
                .onStatus(status -> !status.is2xxSuccessful() && !status.isError(),
                        (request, response) -> {
                            throw new RestClientResponseException("slack webhook answered non-2xx",
                                    response.getStatusCode(), response.getStatusText(), null, null, null);
                        })
                .toBodilessEntity();   // 2xx가 아니면 예외가 난다
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
