package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.entity.NotificationChannel;
import com.bff.pipeline.model.NotifyClaim;
import com.bff.pipeline.repository.NotifyRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ADR-022 종단 알림의 단일 데몬 loop다 — {@code PipelineScheduler}를 축소 모델링해 워커 풀 fan-out 없이
 * 한 스레드("notify-scheduler")가 sweep마다 최대 한 건을 claim→전달→기록으로 직렬 처리한다(notify는
 * 파이프라인당 1회·저빈도라 충분하고, Slack 호출 상한은 call-timeout이 소유한다). 실행 스케줄러와 스레드·
 * 회계를 공유하지 않는다(격리 — 느린 sink가 파이프라인 실행을 굶기지 않는다).
 *
 * 채널 gate: 매 sweep마다 활성 채널을 새로 읽어(캐시 없음 — 반영 지연 상한 = 1 sweep) 미설정/비활성이면
 * claim 자체를 하지 않고 idle한다 — attempts를 소진하지 않아 backlog가 보존되고 채널 (재)활성화 시 소급
 * 발화한다. 전달은 트랜잭션 밖에서 일어나고, 성공은 {@code NotifyWriteBack.onSuccess}, 실패
 * (RuntimeException)는 WARN 구조화 로그 후 {@code onFailure}(backoff/give-up)로 기록한다.
 *
 * 케이던스: 일감이 있었으면 pollInterval(idle backoff 리셋), 빈 sweep이면 maxIdleSleep 상한의 geometric
 * idle backoff. sweep 자체 예외는 WARN으로 잡아 루프를 유지하고, 종료({@code loop.isShutdown()}) 후에는
 * 재예약하지 않는다. master switch({@code pipeline.notify.enabled=false})면 아예 돌지 않는다.
 *
 * give-up 경보 폴링(ADR-022 §4 배포 게이트의 V1 최소 구현): actuator가 없으므로 이 loop가
 * {@code countGivenUp}을 주기 폴링해 0 초과면 ERROR 로그로 승격한다 — 정규 소스는 로그가 아니라 DB 파생
 * 술어이며, 이 폴링이 그 술어를 주기 평가하는 최소 배선이다.
 */
@Slf4j
@Component
public class NotifyScheduler {

    /** give-up 경보 폴링 주기 — sweep 케이던스와 무관하게 이 간격마다 한 번만 countGivenUp을 조회한다. */
    private static final Duration GIVE_UP_ALERT_POLL_INTERVAL = Duration.ofMinutes(5);

    private final NotifyClaimer notifyClaimer;
    private final SlackNotifier slackNotifier;
    private final NotifyWriteBack notifyWriteBack;
    private final NotificationChannelService channels;
    private final NotifyRepository notifyRepository;
    private final NotifySettings settings;
    private final Clock clock;

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notify-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private Duration idleBackoff;
    private Instant nextGiveUpAlertPollAt;

    public NotifyScheduler(NotifyClaimer notifyClaimer, SlackNotifier slackNotifier, NotifyWriteBack notifyWriteBack,
            NotificationChannelService channels, NotifyRepository notifyRepository, NotifySettings settings,
            Clock clock) {
        this.notifyClaimer = notifyClaimer;
        this.slackNotifier = slackNotifier;
        this.notifyWriteBack = notifyWriteBack;
        this.channels = channels;
        this.notifyRepository = notifyRepository;
        this.settings = settings;
        this.clock = clock;
        this.idleBackoff = settings.backoffBase();
        this.nextGiveUpAlertPollAt = clock.instant();   // 첫 sweep에서 즉시 1회 폴링
    }

    @PostConstruct
    void start() {
        if (!settings.enabled()) {
            log.info("notify scheduler disabled (pipeline.notify.enabled=false)");
            return;
        }
        loop.schedule(this::runSweep, settings.schedulerInitialDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        loop.shutdownNow();
    }

    void runSweep() {
        boolean delivered = false;
        try {
            alertOnGivenUpBacklog();
            delivered = deliverOne();
        } catch (RuntimeException sweepFailure) {
            log.warn("notify sweep failed", sweepFailure);
        } finally {
            Duration delay = nextDelay(delivered);
            if (!loop.isShutdown()) {   // @PreDestroy 종료 후 재예약 → RejectedExecutionException 방지
                loop.schedule(this::runSweep, delay.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 활성 채널이 있으면 한 건 claim→전달→기록한다. 반환값은 "일감이 있었나"다 — 채널 gate가 닫혔거나
     * claim할 행이 없으면 false(idle backoff 대상). 전달 실패는 WARN 구조화 로그(응답 분류 = 예외 클래스)
     * 후 backoff 기록으로 흡수한다 — 실패가 sweep을 죽이지 않는다. catch의 범위는 전달 호출뿐이다 —
     * attempts++는 "실제로 호출했으나 실패"로 한정되므로(ADR-022 §2), 전달 성공 후의 onSuccess 실패는
     * 전달 실패로 기록하지 않고 runSweep으로 전파한다(lease가 남아 만료 후 재claim → at-least-once 중복 수용).
     */
    boolean deliverOne() {
        Optional<NotificationChannel> activeChannel = channels.activeChannel();
        if (activeChannel.isEmpty()) {
            return false;   // 미설정/비활성 → claim 없이 idle (backlog age 지표가 드러낸다)
        }
        Optional<NotifyClaim> claim = notifyClaimer.claimOne();
        if (claim.isEmpty()) {
            return false;
        }
        NotifyClaim claimed = claim.get();
        try {
            slackNotifier.deliver(activeChannel.get().getSlackWebhookUrl(), claimed.payload());
        } catch (RuntimeException deliveryFailed) {   // harness-allow: targeted-catch — sink 경계: 모든 전달 실패를 backoff/give-up으로 수렴, 에스컬레이션은 give-up 경보(spec §4.4/§5)
            log.warn("notify delivery failed pipeline={} status={} sink=slack resp_class={}",
                    claimed.pipelineId(), claimed.payload().terminalStatus(),
                    deliveryFailed.getClass().getSimpleName(), deliveryFailed);
            notifyWriteBack.onFailure(claimed.pipelineId(), claimed.token());
            return true;
        }
        notifyWriteBack.onSuccess(claimed.pipelineId(), claimed.token());
        return true;
    }

    /** 일감이 있었으면 pollInterval(백오프 리셋), 빈 sweep이면 maxIdleSleep 상한의 geometric idle backoff. */
    Duration nextDelay(boolean delivered) {
        if (delivered) {
            idleBackoff = settings.backoffBase();
            return settings.pollInterval();
        }
        idleBackoff = min(idleBackoff.multipliedBy(2), settings.maxIdleSleep());
        return idleBackoff;
    }

    /** give-up 경보의 최소 배선 — 폴링 주기가 지났으면 countGivenUp을 조회하고 0 초과면 ERROR로 승격한다. */
    private void alertOnGivenUpBacklog() {
        Instant now = clock.instant();
        if (now.isBefore(nextGiveUpAlertPollAt)) {
            return;
        }
        nextGiveUpAlertPollAt = now.plus(GIVE_UP_ALERT_POLL_INTERVAL);
        long givenUpCount = notifyRepository.countGivenUp(settings.maxAttempts());
        if (givenUpCount > 0) {
            log.error("notify give-up backlog count={} sink=slack — 자동 재시도가 멈춘 종단 알림. "
                    + "운영자 개입 필요(notify_attempts 리셋 후 notify_next_at을 now로)", givenUpCount);
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
