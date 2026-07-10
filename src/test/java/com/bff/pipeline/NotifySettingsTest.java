package com.bff.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.NotifySettings;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link NotifySettings}가 잘못된 설정 조합을 서버가 뜨는 시점에 바로 실패시키는지 검증한다.
 * 알림을 켜는(enabled=true) 배포는 Slack webhook 주소, 도입 시각(enabledAfter), 배포 환경 이름,
 * 상세 화면 주소 base를 전부 줘야 하고, 빠뜨리면 어느 키가 문제인지 에러 메시지에 담아 실패한다.
 * 꺼진 배포는 전부 생략할 수 있다.
 */
class NotifySettingsTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";
    private static final Instant ENABLED_AFTER = Instant.parse("2026-07-09T00:00:00Z");
    private static final String ENVIRONMENT = "stg";
    private static final String DETAIL_URL_BASE = "http://localhost:3001/integration/admin/pipelines";

    @Test
    void anEnabledNotifierWithAllRequiredValuesConstructs() {
        assertThatCode(() -> new NotifySettings(true, WEBHOOK_URL, ENABLED_AFTER, ENVIRONMENT, DETAIL_URL_BASE))
                .doesNotThrowAnyException();
    }

    @Test
    void anEnabledNotifierWithABlankWebhookFailsFastWithItsKey() {
        assertThatThrownBy(() -> new NotifySettings(true, " ", ENABLED_AFTER, ENVIRONMENT, DETAIL_URL_BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.slack-webhook-url");
    }

    @Test
    void anEnabledNotifierWithoutAnAdoptionCutoffFailsFastWithItsKey() {
        assertThatThrownBy(() -> new NotifySettings(true, WEBHOOK_URL, null, ENVIRONMENT, DETAIL_URL_BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.enabled-after");
    }

    @Test
    void anEnabledNotifierWithABlankEnvironmentFailsFastWithItsKey() {
        assertThatThrownBy(() -> new NotifySettings(true, WEBHOOK_URL, ENABLED_AFTER, " ", DETAIL_URL_BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.environment");
    }

    @Test
    void anEnabledNotifierWithABlankDetailUrlBaseFailsFastWithItsKey() {
        assertThatThrownBy(() -> new NotifySettings(true, WEBHOOK_URL, ENABLED_AFTER, ENVIRONMENT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.detail-url-base");
    }

    @Test
    void aDisabledNotifierMayOmitEverything() {
        assertThatCode(() -> new NotifySettings(false, null, null, null, null)).doesNotThrowAnyException();
    }
}
