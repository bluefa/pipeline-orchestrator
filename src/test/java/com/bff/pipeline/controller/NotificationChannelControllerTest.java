package com.bff.pipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.dto.NotifyHealthView;
import com.bff.pipeline.dto.TestResult;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.repository.NotificationChannelRepository;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.service.notify.NotificationChannelService;
import com.bff.pipeline.service.notify.SlackNotifier;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
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
 * 없음), 테스트 전송이 예외 대신 항상 결과 본문으로 응답하는 규약(전달 실패 → delivered=false + 사유,
 * webhook 미설정 → "channel not configured", 성공 → delivered=true), probe gate가 webhook 설정 기준이라
 * 비활성 채널에서도 테스트 전송이 실행되는 "테스트 후 활성화" 순서, 그리고 give-up 경보 폴링 표면인
 * health 조회의 위임. Slack HTTP 경계는 스크립트된 응답을 돌려주는 가짜 요청 팩토리 위의 진짜 RestClient로
 * 대체한다(GlobalAdviceTest처럼 컨트롤러를 직접 호출).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationChannelService.class, NotificationChannelControllerTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationChannelControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/verySecretToken";
    private static final int MAX_ATTEMPTS = 3;

    @Autowired private NotificationChannelService channelService;
    @Autowired private NotificationChannelRepository repository;
    @Autowired private PipelineRepository pipelineRepository;

    @AfterEach
    void clean() {
        repository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void aTestSendWithoutAConfiguredWebhookReportsChannelNotConfigured() {
        NotificationChannelController controller = controllerAgainstSlackResponding(HttpStatus.OK);

        TestResult beforeAnyRow = controller.test();
        assertThat(beforeAnyRow.delivered()).isFalse();
        assertThat(beforeAnyRow.error()).isEqualTo("channel not configured");

        controller.upsert(new ChannelUpsert("#infra-alerts", true, null));   // 행은 있으나 webhook 미설정
        TestResult withoutWebhook = controller.test();
        assertThat(withoutWebhook.delivered()).isFalse();
        assertThat(withoutWebhook.error()).isEqualTo("channel not configured");
    }

    @Test
    void aTestSendProbesEvenADisabledChannelWithAStoredWebhook() {
        NotificationChannelController controller = controllerAgainstSlackResponding(HttpStatus.OK);
        controller.upsert(new ChannelUpsert("#infra-alerts", false, WEBHOOK_URL));   // webhook 저장 + 비활성

        TestResult result = controller.test();

        assertThat(result.delivered()).isTrue();   // probe gate는 webhook 설정 기준 — "테스트 후 활성화" 순서 지원
        assertThat(result.error()).isNull();
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

    @Test
    void theHealthEndpointExposesTheGiveUpBacklogPollingSurface() {
        pipelineRepository.save(Pipeline.builder()
                .type(PipelineType.INSTALL)
                .target("nt-controller-given-up")
                .status(PipelineStatus.DONE)
                .createdAt(NOW.minusSeconds(3600))
                .lastActivityAt(NOW.minusSeconds(600))
                .nextDueAt(NOW)
                .cancelRequested(false)
                .notifyAttempts(MAX_ATTEMPTS)   // give-up 행 — 자동 재시도가 멈췄다
                .build());

        NotifyHealthView health = controllerAgainstSlackResponding(HttpStatus.OK).health();

        assertThat(health.giveUpCount()).isEqualTo(1);
        assertThat(health.oldestDeliveryPendingAt()).isNull();   // give-up 행은 pending age에서 빠진다
        assertThat(health.channelActive()).isFalse();
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

        @Bean
        NotifySettings notifySettings() {
            return NotifySettings.builder()
                    .enabled(true)
                    .pollInterval(Duration.ofSeconds(2))
                    .maxIdleSleep(Duration.ofSeconds(10))
                    .backoffBase(Duration.ofSeconds(5))
                    .backoffMax(Duration.ofMinutes(5))
                    .jitterRatio(0.0)
                    .leaseDuration(Duration.ofMinutes(1))
                    .callTimeout(Duration.ofSeconds(10))
                    .maxAttempts(MAX_ATTEMPTS)
                    .schedulerInitialDelay(Duration.ofSeconds(10))
                    .build();
        }
    }
}
