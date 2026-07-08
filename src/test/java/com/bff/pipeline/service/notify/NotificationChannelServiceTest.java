package com.bff.pipeline.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.entity.NotificationChannel;
import com.bff.pipeline.exception.InvalidNotificationWebhookException;
import com.bff.pipeline.repository.NotificationChannelRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NotificationChannelService}의 upsert 필드 규칙(ADR-022 구현 명세 §6.1)을 검증한다 — 최초 upsert의
 * SINGLETON_ID insert, null/blank webhook의 기존 값 유지, enabled 누락의 typed 400, SSRF 검증(scheme/host
 * 정확 일치/userinfo), secret 마스킹(응답에 원문 없음), 그리고 스케줄러 채널 gate인 activeChannel 술어.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationChannelService.class, NotificationChannelServiceTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationChannelServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/verySecretToken";

    @Autowired private NotificationChannelService service;
    @Autowired private NotificationChannelRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void theFirstUpsertInsertsTheSingletonRow() {
        ChannelView view = service.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        NotificationChannel stored = repository.findById(NotificationChannel.SINGLETON_ID).orElseThrow();
        assertThat(stored.getSlackWebhookUrl()).isEqualTo(WEBHOOK_URL);
        assertThat(stored.getChannelLabel()).isEqualTo("#infra-alerts");
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        assertThat(view.webhookConfigured()).isTrue();
        assertThat(view.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void aNullOrBlankWebhookUrlKeepsTheStoredValue() {
        service.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        service.upsert(new ChannelUpsert("#renamed", false, null));
        assertThat(storedWebhookUrl()).isEqualTo(WEBHOOK_URL);

        service.upsert(new ChannelUpsert("#renamed", false, "   "));
        assertThat(storedWebhookUrl()).isEqualTo(WEBHOOK_URL);
    }

    @Test
    void aProvidedWebhookUrlReplacesTheStoredValue() {
        service.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));
        String replacement = "https://hooks.slack.com/services/T0001/B0002/rotatedToken";

        service.upsert(new ChannelUpsert("#infra-alerts", true, replacement));

        assertThat(storedWebhookUrl()).isEqualTo(replacement);
    }

    @Test
    void anOmittedEnabledIsRejectedAsATypedBadRequest() {
        assertThatExceptionOfType(InvalidNotificationWebhookException.class)
                .isThrownBy(() -> service.upsert(new ChannelUpsert("#infra-alerts", null, WEBHOOK_URL)))
                .satisfies(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("ORCHESTRATION_INVALID_NOTIFICATION_WEBHOOK");
                })
                .withMessageContaining("enabled");
    }

    @Test
    void aNonHttpsWebhookIsRejectedAsATypedBadRequest() {
        assertRejected("http://hooks.slack.com/services/T0001/B0002/token");
    }

    @Test
    void aHostMerelyPrefixedWithTheSlackHostIsRejected() {
        assertRejected("https://hooks.slack.com.evil/services/T0001/B0002/token");
    }

    @Test
    void aWebhookCarryingUserinfoIsRejected() {
        assertRejected("https://hooks.slack.com@evil/services/T0001/B0002/token");
    }

    @Test
    void aRejectedWebhookLeavesTheStoredRowUntouched() {
        service.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        assertRejected("http://hooks.slack.com/services/T0001/B0002/token");

        assertThat(storedWebhookUrl()).isEqualTo(WEBHOOK_URL);
    }

    @Test
    void theViewNeverContainsTheRawWebhookUrl() {
        service.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        ChannelView view = service.view();

        assertThat(view.webhookConfigured()).isTrue();
        assertThat(view.webhookMasked()).isNotEqualTo(WEBHOOK_URL);
        assertThat(view.webhookMasked()).doesNotContain("verySecretToken", "/services/");
        assertThat(view.webhookMasked()).endsWith("oken");   // 뒤 4자만 보인다
    }

    @Test
    void theViewBeforeAnyUpsertShowsAnUnconfiguredChannel() {
        ChannelView view = service.view();

        assertThat(view.enabled()).isFalse();
        assertThat(view.webhookConfigured()).isFalse();
        assertThat(view.webhookMasked()).isNull();
        assertThat(view.updatedAt()).isNull();
    }

    @Test
    void activeChannelRequiresEnabledAndAConfiguredWebhook() {
        service.upsert(new ChannelUpsert("#infra-alerts", false, WEBHOOK_URL));
        assertThat(service.activeChannel()).isEmpty();            // 비활성 → gate 닫힘

        service.upsert(new ChannelUpsert("#infra-alerts", true, null));
        assertThat(service.activeChannel()).isPresent();          // 활성 + webhook 유지 → gate 열림
    }

    @Test
    void anEnabledChannelWithoutAWebhookIsNotActive() {
        service.upsert(new ChannelUpsert("#infra-alerts", true, null));   // 최초 upsert, webhook 없이

        assertThat(service.activeChannel()).isEmpty();
    }

    private String storedWebhookUrl() {
        return repository.findById(NotificationChannel.SINGLETON_ID).orElseThrow().getSlackWebhookUrl();
    }

    private void assertRejected(String webhookUrl) {
        assertThatExceptionOfType(InvalidNotificationWebhookException.class)
                .isThrownBy(() -> service.upsert(new ChannelUpsert("#infra-alerts", true, webhookUrl)))
                .satisfies(exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
