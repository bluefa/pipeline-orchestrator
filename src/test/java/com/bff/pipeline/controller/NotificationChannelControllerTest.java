package com.bff.pipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.dto.TestResult;
import com.bff.pipeline.repository.NotificationChannelRepository;
import com.bff.pipeline.service.notify.NotificationChannelService;
import com.bff.pipeline.service.notify.SlackNotifier;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * {@link NotificationChannelController}의 계약(ADR-022 §6.1)을 검증한다 — GET 응답의 webhook 마스킹(원문
 * 없음)과, 테스트 전송이 예외 대신 항상 결과 본문으로 응답하는 규약(전달 실패 → delivered=false + 사유,
 * 활성 채널 없음 → "channel not configured", 성공 → delivered=true). Slack HTTP 경계는 스크립트된 응답을
 * 돌려주는 가짜 요청 팩토리 위의 진짜 RestClient로 대체한다(GlobalAdviceTest처럼 컨트롤러를 직접 호출).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationChannelService.class, NotificationChannelControllerTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationChannelControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/verySecretToken";

    @Autowired private NotificationChannelService channelService;
    @Autowired private NotificationChannelRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void aTestSendWithoutAnActiveChannelReportsChannelNotConfigured() {
        TestResult result = controllerAgainstSlackResponding(HttpStatus.OK).test();

        assertThat(result.delivered()).isFalse();
        assertThat(result.error()).isEqualTo("channel not configured");
    }

    @Test
    void aDeliveryFailureStillAnswersWithAResultBodyInsteadOfAnException() {
        channelService.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        TestResult result = controllerAgainstSlackResponding(HttpStatus.INTERNAL_SERVER_ERROR).test();

        assertThat(result.delivered()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    @Test
    void aDeliveryFailureMessageNeverLeaksTheRawWebhookUrl() {
        channelService.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        TestResult result = controllerAgainstSlackFailingWithIoError().test();

        assertThat(result.delivered()).isFalse();
        // ResourceAccessException 메시지는 요청 URL 전문을 싣는다 — webhook은 secret이라 응답에서 지워져야 한다.
        assertThat(result.error()).doesNotContain(WEBHOOK_URL, "verySecretToken");
        assertThat(result.error()).contains("<webhook>");
    }

    @Test
    void aSuccessfulTestDeliveryReportsDeliveredWithoutAnError() {
        NotificationChannelController controller = controllerAgainstSlackResponding(HttpStatus.OK);
        controller.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        TestResult result = controller.test();

        assertThat(result.delivered()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    void theGetResponseCarriesOnlyTheMaskedWebhook() {
        NotificationChannelController controller = controllerAgainstSlackResponding(HttpStatus.OK);
        controller.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        ChannelView view = controller.view();

        assertThat(view.webhookConfigured()).isTrue();
        assertThat(view.webhookMasked()).isNotEqualTo(WEBHOOK_URL);
        assertThat(view.webhookMasked()).doesNotContain("verySecretToken", "/services/");
    }

    /** 컨트롤러를 GlobalAdviceTest처럼 직접 만든다 — Slack만 지정된 상태 코드를 돌려주는 스크립트로 대체. */
    private NotificationChannelController controllerAgainstSlackResponding(HttpStatus slackResponseStatus) {
        RestClient scriptedRestClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
                    request.setResponse(new MockClientHttpResponse(new byte[0], slackResponseStatus));
                    return request;
                })
                .build();
        return new NotificationChannelController(channelService, new SlackNotifier(scriptedRestClient));
    }

    /** I/O 실패(연결 불가 등)를 스크립트한다 — RestClient가 요청 URL 전문을 실은 ResourceAccessException으로 감싼다. */
    private NotificationChannelController controllerAgainstSlackFailingWithIoError() {
        RestClient failingRestClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IOException("connection refused");
                })
                .build();
        return new NotificationChannelController(channelService, new SlackNotifier(failingRestClient));
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
