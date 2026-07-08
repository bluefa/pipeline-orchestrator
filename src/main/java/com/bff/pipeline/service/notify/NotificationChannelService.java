package com.bff.pipeline.service.notify;

import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.entity.NotificationChannel;
import com.bff.pipeline.exception.InvalidNotificationWebhookException;
import com.bff.pipeline.repository.NotificationChannelRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slack 알림 채널 설정(단일 행, {@code id = SINGLETON_ID})을 관리하는 서비스다(ADR-022 §6.1).
 *
 * {@code activeChannel()}은 enabled이면서 webhook URL이 있는 행만 돌려준다 — notify 스케줄러의 채널 gate로,
 * 비어 있으면 스케줄러는 claim 없이 idle한다. {@code upsert()}는 수동 @Id 규약대로 로드-또는-생성 후 save한다
 * (set-id 엔티티를 곧장 save하면 Hibernate가 detached merge를 하므로 먼저 load해 명확히 한다). upsert 규칙:
 * {@code slack_webhook_url}이 null/blank면 저장된 값을 유지하고, 값이 오면 SSRF 검증 후 교체한다;
 * {@code enabled}는 필수(null이면 400); 성공하면 {@code updated_at}을 주입된 Clock으로 갱신한다.
 *
 * SSRF 방어(필수): webhook은 https 정확 scheme, host가 정확히 hooks.slack.com(부분 일치 금지 —
 * hooks.slack.com.evil 우회 차단), userinfo 없음(https://hooks.slack.com@evil 차단)을 모두 만족해야 한다.
 * "admin이 넣는 값이라 안전"에 기대지 않는다 — 서버가 임의 URL로 POST하면 SSRF다.
 *
 * webhook은 secret이다 — 조회 응답({@link ChannelView})에는 원문 대신 설정 여부와 뒤 4자 마스킹만 싣는다.
 */
@Service
public class NotificationChannelService {

    private static final String REQUIRED_WEBHOOK_SCHEME = "https";
    private static final String ALLOWED_WEBHOOK_HOST = "hooks.slack.com";
    private static final int MASK_VISIBLE_SUFFIX_LENGTH = 4;
    private static final String MASK_PREFIX = "https://hooks.slack.com/…/";

    private final NotificationChannelRepository channels;
    private final Clock clock;

    public NotificationChannelService(NotificationChannelRepository channels, Clock clock) {
        this.channels = channels;
        this.clock = clock;
    }

    /** notify 스케줄러의 채널 gate — enabled이면서 webhook URL이 설정된 행만. 없으면 empty(스케줄러 idle). */
    public Optional<NotificationChannel> activeChannel() {
        return channels.findById(NotificationChannel.SINGLETON_ID)
                .filter(NotificationChannelService::isDeliverable);
    }

    /** admin 조회용 마스킹 뷰. 행이 아직 없으면 미설정 상태(enabled=false, configured=false)로 내려간다. */
    public ChannelView view() {
        return channels.findById(NotificationChannel.SINGLETON_ID)
                .map(NotificationChannelService::toView)
                .orElseGet(() -> ChannelView.builder().enabled(false).webhookConfigured(false).build());
    }

    @Transactional
    public ChannelView upsert(ChannelUpsert request) {
        if (request.enabled() == null) {
            throw new InvalidNotificationWebhookException("enabled is required (must be true or false)");
        }
        NotificationChannel channel = channels.findById(NotificationChannel.SINGLETON_ID)
                .orElseGet(() -> NotificationChannel.builder().id(NotificationChannel.SINGLETON_ID).build());
        if (isProvided(request.slackWebhookUrl())) {
            channel.setSlackWebhookUrl(requireValidWebhookUrl(request.slackWebhookUrl()));
        }
        channel.setChannelLabel(request.channelLabel());
        channel.setEnabled(request.enabled());
        channel.setUpdatedAt(clock.instant());
        return toView(channels.save(channel));
    }

    private static boolean isDeliverable(NotificationChannel channel) {
        return channel.isEnabled() && channel.getSlackWebhookUrl() != null;
    }

    private static boolean isProvided(String webhookUrl) {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** SSRF 검증 — https 정확 scheme AND host 정확 일치 AND userinfo 없음. 위반은 typed 400. */
    private static String requireValidWebhookUrl(String webhookUrl) {
        URI parsed;
        try {
            parsed = new URI(webhookUrl);
        } catch (URISyntaxException invalidSyntax) {
            throw new InvalidNotificationWebhookException("slack_webhook_url is not a valid URI");
        }
        if (!REQUIRED_WEBHOOK_SCHEME.equalsIgnoreCase(parsed.getScheme())) {
            throw new InvalidNotificationWebhookException("slack_webhook_url must use the https scheme");
        }
        if (parsed.getUserInfo() != null) {
            throw new InvalidNotificationWebhookException("slack_webhook_url must not contain userinfo");
        }
        if (!ALLOWED_WEBHOOK_HOST.equalsIgnoreCase(parsed.getHost())) {
            throw new InvalidNotificationWebhookException(
                    "slack_webhook_url host must be exactly " + ALLOWED_WEBHOOK_HOST);
        }
        return webhookUrl;
    }

    private static ChannelView toView(NotificationChannel channel) {
        return ChannelView.builder()
                .channelLabel(channel.getChannelLabel())
                .enabled(channel.isEnabled())
                .webhookConfigured(channel.getSlackWebhookUrl() != null)
                .webhookMasked(maskWebhookUrl(channel.getSlackWebhookUrl()))
                .updatedAt(channel.getUpdatedAt())
                .build();
    }

    /** secret 마스킹 — 저장된 webhook의 뒤 4자만 남긴다(원문은 어떤 응답에도 싣지 않는다). */
    private static String maskWebhookUrl(String webhookUrl) {
        if (webhookUrl == null) {
            return null;
        }
        String visibleSuffix = webhookUrl.length() <= MASK_VISIBLE_SUFFIX_LENGTH
                ? webhookUrl
                : webhookUrl.substring(webhookUrl.length() - MASK_VISIBLE_SUFFIX_LENGTH);
        return MASK_PREFIX + visibleSuffix;
    }
}
