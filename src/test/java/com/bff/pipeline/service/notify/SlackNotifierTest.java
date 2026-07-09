package com.bff.pipeline.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.dto.NotifyPayload;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link SlackNotifier} 순수 단위 테스트다. 메시지 형식(상태별 이모지/색, FAILED 전용 필드, 항상 포함되는
 * pipeline_id, null type 열화)은 package-private {@code toSlackMessage}로 직접 단언하고, HTTP 경계(2xx 성공,
 * 비2xx는 RestClientException)는 스크립트된 응답을 돌려주는 가짜 요청 팩토리 위의 진짜 RestClient로 검증한다.
 */
class SlackNotifierTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";

    @Test
    void aDoneMessageCarriesCheckmarkGoodColorAndThePipelineId() {
        Map<String, Object> message = SlackNotifier.toSlackMessage(donePayload());

        assertThat((String) message.get("text"))
                .contains(":white_check_mark:", "*Pipeline DONE*", "INSTALL", "(id 1234)");
        assertThat(color(message)).isEqualTo("good");
        assertThat(fieldTitles(message)).containsExactly("type", "status", "target_ref");
        assertThat(fieldValue(message, "target_ref")).isEqualTo("tgt_9f3a");
    }

    @Test
    void aFailedMessageCarriesFailureFieldsAndDangerColor() {
        NotifyPayload payload = NotifyPayload.builder()
                .pipelineId(77L).type("DELETE").terminalStatus("FAILED").targetRef("tgt_9f3a")
                .failedTask("AWS_SERVICE_DESTROY_V1").errorCode("CHECK_ERROR")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();

        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);

        assertThat((String) message.get("text")).contains(":x:", "*Pipeline FAILED*", "(id 77)");
        assertThat(color(message)).isEqualTo("danger");
        assertThat(fieldTitles(message)).containsExactly("type", "status", "target_ref", "failed_task", "error_code");
        assertThat(fieldValue(message, "failed_task")).isEqualTo("AWS_SERVICE_DESTROY_V1");
        assertThat(fieldValue(message, "error_code")).isEqualTo("CHECK_ERROR");
    }

    @Test
    void aCancelledMessageCarriesNoEntryAndWarningColor() {
        NotifyPayload payload = NotifyPayload.builder()
                .pipelineId(9L).type("CUSTOM").terminalStatus("CANCELLED").targetRef("tgt_1")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();

        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);

        assertThat((String) message.get("text")).contains(":no_entry:", "*Pipeline CANCELLED*", "(id 9)");
        assertThat(color(message)).isEqualTo("warning");
        assertThat(fieldTitles(message)).doesNotContain("failed_task", "error_code");
    }

    @Test
    void aDegradedNullTypeIsOmittedInsteadOfPrintedAsNull() {
        NotifyPayload payload = NotifyPayload.builder()
                .pipelineId(5L).type(null).terminalStatus("DONE").targetRef("tgt_1")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();

        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);

        assertThat((String) message.get("text")).doesNotContain("null").contains("(id 5)");
        assertThat(fieldTitles(message)).doesNotContain("type");
    }

    @Test
    void aSuccessfulDeliveryPostsTheSlackJsonBodyToTheWebhook() throws Exception {
        ScriptedRequestFactory slackEndpoint = new ScriptedRequestFactory(HttpStatus.OK);
        SlackNotifier notifier = new SlackNotifier(restClientOver(slackEndpoint));

        assertThatCode(() -> notifier.deliver(WEBHOOK_URL, donePayload())).doesNotThrowAnyException();

        assertThat(slackEndpoint.lastRequest.getURI()).isEqualTo(URI.create(WEBHOOK_URL));
        assertThat(slackEndpoint.lastRequest.getBodyAsString())
                .contains(":white_check_mark:", "\"attachments\"", "\"target_ref\"");
    }

    @Test
    void aNonTwoHundredResponseRaisesARestClientException() {
        SlackNotifier notifier = new SlackNotifier(
                restClientOver(new ScriptedRequestFactory(HttpStatus.INTERNAL_SERVER_ERROR)));

        assertThatThrownBy(() -> notifier.deliver(WEBHOOK_URL, donePayload()))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void aRedirectResponseIsADeliveryFailureNotAnAck() {
        // ack는 2xx뿐이다(ADR-022 §2) — 3xx가 성공으로 통과하면 notified_at이 잘못 찍혀 알림이 조용히 유실된다.
        SlackNotifier notifier = new SlackNotifier(
                restClientOver(new ScriptedRequestFactory(HttpStatus.MOVED_PERMANENTLY)));

        assertThatThrownBy(() -> notifier.deliver(WEBHOOK_URL, donePayload()))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("non-2xx");
    }

    private static NotifyPayload donePayload() {
        return NotifyPayload.builder()
                .pipelineId(1234L).type("INSTALL").terminalStatus("DONE").targetRef("tgt_9f3a")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();
    }

    private static RestClient restClientOver(ClientHttpRequestFactory requestFactory) {
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attachment(Map<String, Object> message) {
        return ((List<Map<String, Object>>) message.get("attachments")).getFirst();
    }

    private static String color(Map<String, Object> message) {
        return (String) attachment(message).get("color");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> message) {
        return (List<Map<String, Object>>) attachment(message).get("fields");
    }

    private static List<String> fieldTitles(Map<String, Object> message) {
        return fields(message).stream().map(field -> (String) field.get("title")).toList();
    }

    private static String fieldValue(Map<String, Object> message, String title) {
        return fields(message).stream()
                .filter(field -> title.equals(field.get("title")))
                .map(field -> (String) field.get("value"))
                .findFirst().orElseThrow();
    }

    /** 항상 지정된 상태 코드를 돌려주는 스크립트 요청 팩토리 — 마지막 요청을 붙잡아 URI/본문을 단언하게 한다. */
    static final class ScriptedRequestFactory implements ClientHttpRequestFactory {
        private final HttpStatus responseStatus;
        MockClientHttpRequest lastRequest;

        ScriptedRequestFactory(HttpStatus responseStatus) {
            this.responseStatus = responseStatus;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
            request.setResponse(new MockClientHttpResponse(new byte[0], responseStatus));
            lastRequest = request;
            return request;
        }
    }
}
