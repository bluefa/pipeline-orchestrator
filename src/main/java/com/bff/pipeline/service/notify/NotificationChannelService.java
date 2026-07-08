package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.dto.NotifyHealthView;
import com.bff.pipeline.entity.NotificationChannel;
import com.bff.pipeline.exception.InvalidNotificationWebhookException;
import com.bff.pipeline.exception.NotificationChannelConflictException;
import com.bff.pipeline.repository.NotificationChannelRepository;
import com.bff.pipeline.repository.NotifyRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slack 알림 채널 설정(단일 행, {@code id = SINGLETON_ID})을 관리하는 서비스다(ADR-022 §6.1).
 *
 * {@code activeChannel()}은 enabled이면서 webhook URL이 있는 행만 돌려준다 — notify 스케줄러의 채널 gate로,
 * 비어 있으면 스케줄러는 claim 없이 idle한다. {@code configuredWebhookUrl()}은 enabled와 무관하게 webhook이
 * 저장된 행의 URL을 돌려준다 — admin 테스트 전송의 gate로, "테스트로 확인한 뒤 활성화"하는 운영 순서를
 * 지원한다(전달 스케줄러의 gate는 여전히 activeChannel이다). {@code upsert()}는 수동 @Id 규약대로
 * 로드-또는-생성 후 save한다(set-id 엔티티를 곧장 save하면 Hibernate가 detached merge를 하므로 먼저 load해
 * 명확히 한다). upsert 규칙: {@code slack_webhook_url}이 null/blank면 저장된 값을 유지하고, 값이 오면 SSRF
 * 검증 후 교체한다; {@code enabled}는 필수(null이면 400); webhook·label이 컬럼 길이(엔티티 상수)를 넘으면
 * raw DataIntegrityViolation(500)이 새기 전에 typed 400으로 거절한다; 성공하면 {@code updated_at}을 주입된
 * Clock으로 갱신한다.
 *
 * 롤아웃 컷오프(ADR-022 §5): singleton 행을 처음 생성하는 upsert는 같은 트랜잭션 안에서 레거시 종단·미알림
 * 행 전체에 notified_at을 backfill해 알림 범위 밖으로 뺀다 — 채널 행이 존재하기 전엔 notifier가 절대 발화하지
 * 못하므로(채널 gate) 최초 채널 설정이 곧 알림 도입 시점이고, 이 1회 컷오프가 레거시 종단 행의 소급 발화
 * 폭주를 막는다. 도입 이후 비활성 기간에 쌓인 backlog의 소급 발화(ADR-022 §4의 의도된 동작)는 그대로
 * 보존된다 — 채널이 이미 존재하는 후속 upsert(disable→enable 포함)는 backfill하지 않는다.
 *
 * {@code health()}는 give-up 경보(배포 게이트)의 정규 소스인 DB 파생 술어의 HTTP 표면이다 — 로그는 유실·수집
 * 누락이 가능해 정규 소스가 아니고, 조직 alerting 스택/admin 대시보드가 이 조회를 주기 폴링한다.
 *
 * SSRF 방어(필수): webhook은 https 정확 scheme, host가 정확히 hooks.slack.com(부분 일치 금지 —
 * hooks.slack.com.evil 우회 차단), userinfo 없음(https://hooks.slack.com@evil 차단)을 모두 만족해야 한다.
 * "admin이 넣는 값이라 안전"에 기대지 않는다 — 서버가 임의 URL로 POST하면 SSRF다.
 *
 * webhook은 secret이다 — 조회 응답({@link ChannelView})에는 원문 대신 설정 여부와 뒤 4자 마스킹만 싣는다.
 */
@Slf4j
@Service
public class NotificationChannelService {

    private static final String REQUIRED_WEBHOOK_SCHEME = "https";
    private static final String ALLOWED_WEBHOOK_HOST = "hooks.slack.com";
    private static final int MASK_VISIBLE_SUFFIX_LENGTH = 4;
    private static final String MASK_PREFIX = "https://hooks.slack.com/…/";

    private final NotificationChannelRepository channels;
    private final NotifyRepository notifyRepository;
    private final NotifySettings settings;
    private final Clock clock;

    public NotificationChannelService(NotificationChannelRepository channels, NotifyRepository notifyRepository,
            NotifySettings settings, Clock clock) {
        this.channels = channels;
        this.notifyRepository = notifyRepository;
        this.settings = settings;
        this.clock = clock;
    }

    /** notify 스케줄러의 채널 gate — enabled이면서 webhook URL이 설정된 행만. 없으면 empty(스케줄러 idle). */
    public Optional<NotificationChannel> activeChannel() {
        return channels.findById(NotificationChannel.SINGLETON_ID)
                .filter(NotificationChannelService::isDeliverable);
    }

    /**
     * admin 테스트 전송의 gate — enabled와 무관하게 webhook이 저장돼 있으면 그 URL을 돌려준다. probe는 채널
     * 활성화 전에 webhook이 맞는지 확인하는 용도라("테스트 후 활성화" 순서) enabled를 요구하지 않는다.
     * 실제 전달 스케줄러의 채널 gate는 여전히 enabled 필수인 {@link #activeChannel()}이다.
     */
    public Optional<String> configuredWebhookUrl() {
        return channels.findById(NotificationChannel.SINGLETON_ID)
                .map(NotificationChannel::getSlackWebhookUrl);
    }

    /** admin 조회용 마스킹 뷰. 행이 아직 없으면 미설정 상태(enabled=false, configured=false)로 내려간다. */
    public ChannelView view() {
        return channels.findById(NotificationChannel.SINGLETON_ID)
                .map(NotificationChannelService::toView)
                .orElseGet(() -> ChannelView.builder().enabled(false).webhookConfigured(false).build());
    }

    /**
     * give-up 경보(배포 게이트)의 정규 소스인 DB 파생 술어를 HTTP로 노출하는 조회다(ADR-022 §4, 구현 명세 §7).
     * 조직 alerting 스택/admin 대시보드가 이 표면을 주기 폴링해 give_up_count가 0을 넘으면 담당자를 page한다 —
     * 로그는 유실·수집 누락·경보 배선 전 발생이 가능하므로 정규 소스로 삼지 않는다(actuator gauge 승격은 후속).
     * 두 age의 기준 시각을 구분해 싣는다 — oldest_unnotified_at은 총 backlog(비활성 채널 backlog 포함),
     * oldest_delivery_pending_at은 due 행만 본 전달 정체이고, channel_active가 후자의 경보 suppress 판단
     * 근거다(비활성이면 정체가 아니라 gate 때문이므로 suppress).
     */
    public NotifyHealthView health() {
        return NotifyHealthView.builder()
                .giveUpCount(notifyRepository.countGivenUp(settings.maxAttempts()))
                .oldestUnnotifiedAt(notifyRepository.oldestUnnotifiedAt(settings.maxAttempts()).orElse(null))
                .oldestDeliveryPendingAt(
                        notifyRepository.oldestDeliveryPending(settings.maxAttempts(), clock.instant()).orElse(null))
                .channelActive(activeChannel().isPresent())
                .build();
    }

    @Transactional
    public ChannelView upsert(ChannelUpsert request) {
        if (request.enabled() == null) {
            throw new InvalidNotificationWebhookException("enabled is required (must be true or false)");
        }
        requireStorableChannelLabel(request.channelLabel());
        Optional<NotificationChannel> existing = channels.findById(NotificationChannel.SINGLETON_ID);
        NotificationChannel channel = existing
                .orElseGet(() -> NotificationChannel.builder().id(NotificationChannel.SINGLETON_ID).build());
        if (isProvided(request.slackWebhookUrl())) {
            channel.setSlackWebhookUrl(requireValidWebhookUrl(request.slackWebhookUrl()));
        }
        channel.setChannelLabel(request.channelLabel());
        channel.setEnabled(request.enabled());
        channel.setUpdatedAt(clock.instant());
        ChannelView view = toView(persistChannel(channel, existing.isEmpty()));
        if (existing.isEmpty()) {
            markLegacyTerminalRowsOutOfScope();
        }
        return view;
    }

    /**
     * 저장 — 최초 생성 경로는 saveAndFlush로 즉시 flush해 singleton PK 경쟁을 이 지점에서 표면화하고,
     * 경쟁에서 진 요청은 raw 중복 키 예외 대신 typed 409({@link NotificationChannelConflictException})로
     * 번역한다 — raw 인프라 예외가 서비스 경계를 넘지 않는다(AGENTS.md 규칙 4, {@code PipelineCreator}
     * idiom). 잔여 창: 상대 커밋이 findById와 merge flush 사이에 정확히 끼면 insert 대신 update로 합쳐져
     * backfill이 한 번 더 돌 수 있으나, 재실행이 억제하는 것은 그 밀리초 창에 종단된 행뿐이라 단일 운영자
     * admin 규모에서 수용한다(발생 조건 = 동시 "최초" 설정 요청 둘).
     */
    private NotificationChannel persistChannel(NotificationChannel channel, boolean creating) {
        if (!creating) {
            return channels.save(channel);
        }
        try {
            return channels.saveAndFlush(channel);
        } catch (DataIntegrityViolationException creationRace) {
            if (isSingletonRowRace(creationRace)) {
                throw new NotificationChannelConflictException(creationRace);
            }
            throw creationRace;
        }
    }

    /** notification_channel의 제약은 singleton PK 하나뿐이라(길이는 사전 가드), 생성 flush의 제약 위반 = 동시 최초 설정 경쟁이다. */
    static boolean isSingletonRowRace(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 롤아웃 컷오프 backfill(ADR-022 §5) — singleton 행을 처음 생성하는 upsert(알림 도입 시점)에서만, 같은
     * 트랜잭션 안에서 레거시 종단·미알림 행에 notified_at을 찍어 알림 범위 밖으로 뺀다. backfill된 값은
     * "전달됨"이 아니라 "알림 범위 밖(도입 전)"을 뜻하며, 그 구분은 INFO 로그가 감사 기록으로 남긴다.
     * AGENTS.md 규칙 3(수기 SQL/마이그레이션 금지) 때문에 배포 런북 SQL이 아니라 이 코드가 컷오프를 강제한다.
     * 동시 최초 upsert 경쟁은 singleton PK가 한쪽만 insert시키고(진 쪽은 {@code persistChannel}이 typed 409로
     * 번역), backfill 자체가 멱등(미알림 종단 행만 대상)이라 무해하다.
     */
    private void markLegacyTerminalRowsOutOfScope() {
        int backfilledRowCount = notifyRepository.markLegacyTerminalRowsOutOfScope(clock.instant());
        log.info("notify rollout cutoff: backfilled legacy terminal rows count={} — 이 notified_at은 '전달됨'이 "
                + "아니라 '알림 범위 밖(도입 전)'을 뜻한다(ADR-022 §5)", backfilledRowCount);
    }

    private static boolean isDeliverable(NotificationChannel channel) {
        return channel.isEnabled() && channel.getSlackWebhookUrl() != null;
    }

    private static boolean isProvided(String webhookUrl) {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** 컬럼 길이 가드 — 엔티티 @Column(length)과 같은 상수를 검사해 500 대신 typed 400으로 거절한다. */
    private static void requireStorableChannelLabel(String channelLabel) {
        if (channelLabel != null && channelLabel.length() > NotificationChannel.CHANNEL_LABEL_MAX_LENGTH) {
            throw new InvalidNotificationWebhookException("channel_label must not exceed "
                    + NotificationChannel.CHANNEL_LABEL_MAX_LENGTH + " characters");
        }
    }

    /** SSRF 검증 — https 정확 scheme AND host 정확 일치 AND userinfo 없음 — + 컬럼 길이 가드. 위반은 typed 400. */
    private static String requireValidWebhookUrl(String webhookUrl) {
        if (webhookUrl.length() > NotificationChannel.WEBHOOK_URL_MAX_LENGTH) {
            throw new InvalidNotificationWebhookException("slack_webhook_url must not exceed "
                    + NotificationChannel.WEBHOOK_URL_MAX_LENGTH + " characters");
        }
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
