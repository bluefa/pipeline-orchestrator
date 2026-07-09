package com.bff.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.NotifySettings;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link NotifySettings}가 잘못된 설정 조합을 서버가 뜨는 시점에 바로 실패시키는지 검증한다.
 * 알림을 켜는(enabled=true) 배포는 Slack webhook 주소와 도입 시각(enabledAfter)을 반드시 함께 줘야 하고,
 * 빠뜨리면 어느 키가 문제인지 에러 메시지에 담아 실패한다. 꺼진 배포는 둘 다 생략할 수 있다.
 */
class NotifySettingsTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";
    private static final Instant ENABLED_AFTER = Instant.parse("2026-07-09T00:00:00Z");

    @Test
    void anEnabledNotifierWithBothRequiredValuesConstructs() {
        assertThatCode(() -> new NotifySettings(true, WEBHOOK_URL, ENABLED_AFTER))
                .doesNotThrowAnyException();
    }

    @Test
    void anEnabledNotifierWithABlankWebhookFailsFastWithItsKey() {
        assertThatThrownBy(() -> new NotifySettings(true, " ", ENABLED_AFTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.slack-webhook-url");
    }

    @Test
    void anEnabledNotifierWithoutAnAdoptionCutoffFailsFastWithItsKey() {
        assertThatThrownBy(() -> new NotifySettings(true, WEBHOOK_URL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.notify.enabled-after");
    }

    @Test
    void aDisabledNotifierMayOmitBothTheWebhookAndTheAdoptionCutoff() {
        assertThatCode(() -> new NotifySettings(false, null, null)).doesNotThrowAnyException();
    }
}
