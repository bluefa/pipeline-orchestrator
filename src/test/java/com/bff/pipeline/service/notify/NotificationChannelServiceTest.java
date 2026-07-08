package com.bff.pipeline.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.dto.NotifyHealthView;
import com.bff.pipeline.entity.NotificationChannel;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.exception.InvalidNotificationWebhookException;
import com.bff.pipeline.repository.NotificationChannelRepository;
import com.bff.pipeline.repository.PipelineRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
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
 * 정확 일치/userinfo), 컬럼 길이 초과의 typed 400(엔티티 상수와 동일 한계 — 500으로 새지 않음), secret
 * 마스킹(응답에 원문 없음), 스케줄러 채널 gate인 activeChannel 술어와 테스트 전송 gate인 configuredWebhookUrl
 * 술어, 그리고 give-up 경보 폴링 표면인 health 조회(give-up 수·두 age 기준 시각·채널 활성)를 본다.
 * 최초 upsert의 롤아웃 컷오프 backfill이 claim 대상을 실제로 좁히는지는 NotifyLifecycleTest가 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationChannelService.class, NotificationChannelServiceTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationChannelServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/verySecretToken";
    private static final int MAX_ATTEMPTS = 3;

    @Autowired private NotificationChannelService service;
    @Autowired private NotificationChannelRepository repository;
    @Autowired private PipelineRepository pipelineRepository;

    @AfterEach
    void clean() {
        repository.deleteAll();
        pipelineRepository.deleteAll();
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
    void anOverlongWebhookUrlIsRejectedAsATypedBadRequestInsteadOfAColumnViolation() {
        String validPrefix = "https://hooks.slack.com/services/";
        String overlongWebhookUrl = validPrefix
                + "a".repeat(NotificationChannel.WEBHOOK_URL_MAX_LENGTH + 1 - validPrefix.length());
        assertThat(overlongWebhookUrl).hasSize(NotificationChannel.WEBHOOK_URL_MAX_LENGTH + 1);

        assertRejected(overlongWebhookUrl);   // 검증 통과 가능한 형태여도 컬럼 한계 초과면 400
    }

    @Test
    void anOverlongChannelLabelIsRejectedAsATypedBadRequestInsteadOfAColumnViolation() {
        String overlongLabel = "#".repeat(NotificationChannel.CHANNEL_LABEL_MAX_LENGTH + 1);

        assertThatExceptionOfType(InvalidNotificationWebhookException.class)
                .isThrownBy(() -> service.upsert(new ChannelUpsert(overlongLabel, true, WEBHOOK_URL)))
                .satisfies(exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .withMessageContaining("channel_label");
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

    @Test
    void theConfiguredWebhookUrlIgnoresTheEnabledGateButRequiresAStoredWebhook() {
        assertThat(service.configuredWebhookUrl()).isEmpty();                 // 행 없음 → 미설정

        service.upsert(new ChannelUpsert("#infra-alerts", false, WEBHOOK_URL));
        assertThat(service.configuredWebhookUrl()).contains(WEBHOOK_URL);     // 비활성이어도 probe 가능("테스트 후 활성화")

        repository.deleteAll();
        service.upsert(new ChannelUpsert("#infra-alerts", true, null));
        assertThat(service.configuredWebhookUrl()).isEmpty();                 // webhook 없는 행 → 미설정
    }

    @Test
    void healthReportsGiveUpCountBothBacklogAgesAndChannelActivity() {
        savePipeline("nt-health-due", builder -> builder.lastActivityAt(NOW.minusSeconds(100)));
        savePipeline("nt-health-waiting", builder -> builder.lastActivityAt(NOW.minusSeconds(200))
                .notifyNextAt(NOW.plusSeconds(30)));   // 미도래 backoff — 총 backlog에만 포함
        savePipeline("nt-health-given-up", builder -> builder.lastActivityAt(NOW.minusSeconds(1000))
                .notifyAttempts(MAX_ATTEMPTS));

        NotifyHealthView health = service.health();

        assertThat(health.giveUpCount()).isEqualTo(1);
        assertThat(health.oldestUnnotifiedAt()).isEqualTo(NOW.minusSeconds(200));        // 총 backlog(give-up 제외)
        assertThat(health.oldestDeliveryPendingAt()).isEqualTo(NOW.minusSeconds(100));   // due 행만 본 전달 정체
        assertThat(health.channelActive()).isFalse();   // 채널 미설정 — 전달 정체 경보는 suppress 대상
    }

    @Test
    void healthReflectsAnActivatedChannel() {
        service.upsert(new ChannelUpsert("#infra-alerts", true, WEBHOOK_URL));

        assertThat(service.health().channelActive()).isTrue();
    }

    private Pipeline savePipeline(String target, Consumer<Pipeline.PipelineBuilder> customize) {
        Pipeline.PipelineBuilder builder = Pipeline.builder()
                .type(PipelineType.INSTALL)
                .target(target)
                .status(PipelineStatus.DONE)
                .createdAt(NOW.minusSeconds(3600))
                .lastActivityAt(NOW.minusSeconds(60))
                .nextDueAt(NOW)
                .cancelRequested(false);
        customize.accept(builder);
        return pipelineRepository.save(builder.build());
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
