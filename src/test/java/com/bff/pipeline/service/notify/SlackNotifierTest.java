package com.bff.pipeline.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.dto.NotifyPayload;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link SlackNotifier} 단위 테스트다. 메시지 형식(상태별 이모지와 색, FAILED 전용 필드,
 * 항상 들어가는 pipeline_id, type이 null인 옛 데이터 처리)은 package-private {@code toSlackMessage}를
 * 직접 불러 단언한다. HTTP 경계(2xx만 성공, 그 외는 RestClientException)는 정해진 응답을 돌려주는
 * 가짜 요청 팩토리 위에 진짜 RestClient를 올려 검증한다.
 */
class SlackNotifierTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";
    private static final String DETAIL_URL = "http://localhost:3001/integration/admin/pipelines/1234";

    @Test
    void aDoneMessageCarriesCheckmarkGoodColorEnvironmentAndADetailLink() {
        Map<String, Object> message = SlackNotifier.toSlackMessage(donePayload());

        assertThat((String) message.get("text"))
                .contains(":white_check_mark:", "*[prd] Pipeline DONE*", "INSTALL", "(id 1234)")
                .contains("<" + DETAIL_URL + "|상세 보기 →>");
        assertThat(color(message)).isEqualTo("good");
        assertThat(fieldTitles(message)).containsExactly("type", "status", "cloud_provider", "target_source");
        assertThat(fieldValue(message, "target_source")).isEqualTo("483");
        assertThat(fieldValue(message, "cloud_provider")).isEqualTo("GCP");
    }

    @Test
    void aFailedMessageCarriesFailureFieldsAndDangerColor() {
        NotifyPayload payload = NotifyPayload.builder()
                .pipelineId(77L).type("DELETE").terminalStatus("FAILED").targetRef("483")
                .cloudProvider("AWS").environment("prd")
                .failedTask("AWS_SERVICE_DESTROY_V1").errorCode("CHECK_ERROR")
                .detailUrl("http://localhost:3001/integration/admin/pipelines/77")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();

        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);

        assertThat((String) message.get("text")).contains(":x:", "*[prd] Pipeline FAILED*", "(id 77)");
        assertThat(color(message)).isEqualTo("danger");
        assertThat(fieldTitles(message)).containsExactly(
                "type", "status", "cloud_provider", "target_source", "failed_task", "error_code");
        assertThat(fieldValue(message, "failed_task")).isEqualTo("AWS_SERVICE_DESTROY_V1");
        assertThat(fieldValue(message, "error_code")).isEqualTo("CHECK_ERROR");
    }

    @Test
    void aCancelledMessageCarriesNoEntryAndWarningColor() {
        NotifyPayload payload = NotifyPayload.builder()
                .pipelineId(9L).type("CUSTOM").terminalStatus("CANCELLED").targetRef("61")
                .environment("stg").detailUrl("http://localhost:3001/integration/admin/pipelines/9")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();

        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);

        assertThat((String) message.get("text")).contains(":no_entry:", "*[stg] Pipeline CANCELLED*", "(id 9)");
        assertThat(color(message)).isEqualTo("warning");
        assertThat(fieldTitles(message)).doesNotContain("failed_task", "error_code");
    }

    @Test
    void degradedOrMissingOptionalValuesAreOmittedInsteadOfPrintedAsNull() {
        // type을 해석 못 하는 옛 행 + 환경/링크/CSP가 없는 payload — 빠진 값은 그 구간째 사라져야 한다.
        NotifyPayload payload = NotifyPayload.builder()
                .pipelineId(5L).type(null).terminalStatus("DONE").targetRef("61")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION).build();

        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);

        assertThat((String) message.get("text"))
                .doesNotContain("null").contains("(id 5)")
                .doesNotContain("[").doesNotContain("상세 보기");
        assertThat(fieldTitles(message)).doesNotContain("type", "cloud_provider");
    }

    @Test
    void aSuccessfulDeliveryPostsTheSlackJsonBodyToTheWebhook() throws Exception {
        ScriptedRequestFactory slackEndpoint = new ScriptedRequestFactory(HttpStatus.OK);
        SlackNotifier notifier = new SlackNotifier(restClientOver(slackEndpoint));

        assertThatCode(() -> notifier.deliver(WEBHOOK_URL, donePayload())).doesNotThrowAnyException();

        assertThat(slackEndpoint.lastRequest.getURI()).isEqualTo(URI.create(WEBHOOK_URL));
        assertThat(slackEndpoint.lastRequest.getBodyAsString())
                .contains(":white_check_mark:", "\"attachments\"", "\"target_source\"");
    }

    @Test
    void aNonTwoHundredResponseRaisesARestClientExceptionWithTheStatusCode() {
        SlackNotifier notifier = new SlackNotifier(
                restClientOver(new ScriptedRequestFactory(HttpStatus.INTERNAL_SERVER_ERROR)));

        assertThatThrownBy(() -> notifier.deliver(WEBHOOK_URL, donePayload()))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("http 500");
    }

    @Test
    void aRedirectResponseIsADeliveryFailureNotAnAck() {
        // 성공으로 인정하는 응답은 2xx뿐이다 — 3xx가 성공으로 통과하면 notified_at이 잘못 찍혀 알림이 조용히 사라진다.
        SlackNotifier notifier = new SlackNotifier(
                restClientOver(new ScriptedRequestFactory(HttpStatus.MOVED_PERMANENTLY)));

        assertThatThrownBy(() -> notifier.deliver(WEBHOOK_URL, donePayload()))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("http 301");
    }

    @Test
    void aDeliveryFailureNeverExposesTheWebhookUrl() {
        // 연결 실패/타임아웃에서 Spring이 만드는 원본 예외는 메시지에 요청 URL 전체를 담는다.
        // webhook 주소는 비밀값이라, deliver가 던지는 예외에는 메시지에도 원인 체인에도 주소가 남으면 안 된다.
        // 이 차단이 깨지면 TerminalNotifier의 실패 WARN 로그마다 비밀이 찍힌다.
        SlackNotifier notifier = new SlackNotifier(restClientOver(new BrokenConnectionFactory()));

        assertThatThrownBy(() -> notifier.deliver(WEBHOOK_URL, donePayload()))
                .isInstanceOf(RestClientException.class)
                .hasNoCause()
                .hasMessageContaining("ResourceAccessException")
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain("hooks.slack.com")
                        .doesNotContain("token"));
    }

    private static NotifyPayload donePayload() {
        return NotifyPayload.builder()
                .pipelineId(1234L).type("INSTALL").terminalStatus("DONE").targetRef("483")
                .cloudProvider("GCP").environment("prd").detailUrl(DETAIL_URL)
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

    /**
     * 요청 실행 시점에 IO 실패를 일으키는 가짜 요청 팩토리. Spring은 이 IOException을
     * 요청 URL 전체가 담긴 메시지의 ResourceAccessException으로 감싸므로,
     * 비밀값 차단 테스트가 실제 유출 경로 그대로를 재현할 수 있다.
     */
    static final class BrokenConnectionFactory implements ClientHttpRequestFactory {
        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            return new MockClientHttpRequest(httpMethod, uri) {
                @Override
                protected ClientHttpResponse executeInternal() throws IOException {
                    throw new IOException("simulated connect timeout");
                }
            };
        }
    }

    /** 항상 지정된 상태 코드를 돌려주는 가짜 요청 팩토리. 마지막 요청을 붙잡아 두어 URI와 본문을 단언할 수 있게 한다. */
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
