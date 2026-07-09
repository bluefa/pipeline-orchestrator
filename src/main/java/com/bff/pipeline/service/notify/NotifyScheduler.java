package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.model.NotifyClaim;
import com.bff.pipeline.repository.NotifyRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 끝난 파이프라인의 알림을 실제로 돌리는 단일 스레드 루프다.
 * {@code PipelineScheduler}를 본떴지만 워커 풀 없이 "notify-scheduler" 스레드 하나가
 * 한 바퀴(sweep)마다 최대 한 건을 점유 → 전달 → 기록 순서로 처리한다.
 * 알림은 파이프라인당 한 번뿐이고 빈도가 낮아 한 스레드로 충분하다.
 * Slack 호출이 오래 걸려도 소요 시간은 call-timeout이 제한한다.
 * 실행 스케줄러와는 스레드도 동시 실행 카운트도 전혀 공유하지 않으므로,
 * Slack이 느려져도 파이프라인 실행이 밀리지 않는다.
 *
 * 알림 채널을 켜고 끄는 문은 {@code start()}의 {@code settings.enabled()} 확인 하나다
 * (오너 결정 2026-07-09 — 채널 설정은 admin 관리 화면 없이 환경변수로 주입한다).
 * 설정은 부팅 때 고정되므로 켜고 끄는 변경은 재시작해야 반영된다.
 * 꺼져 있으면 루프가 아예 돌지 않는다 — 아무것도 점유하지 않고 시도 횟수도 소모하지 않으므로,
 * 꺼진 동안 끝난 파이프라인은 미전송 상태로 쌓여 있다가 켜고 재기동하면 그때 밀린 알림이 나간다.
 * 전달에 쓸 webhook 주소는 {@code settings.slackWebhookUrl()}을 그대로 넘긴다 —
 * 켜진 상태면 값이 반드시 있다는 것을 {@code NotifySettings}가 부팅 시점에 검증하므로
 * 매 바퀴 다시 검사하지 않는다.
 *
 * 전달(Slack 호출)은 트랜잭션 밖에서 한다. 성공하면 {@code NotifyWriteBack.onSuccess}로 기록한다.
 * 실패(RuntimeException)하면 {@code onFailure}로 재시도 간격 또는 자동 재시도 중단을 기록한 뒤,
 * 그 반환값(올라간 시도 횟수)을 실어 WARN 구조화 로그를 남긴다.
 * 로그에 정확한 attempt 값을 싣기 위해 기록이 로그보다 먼저다.
 * 기록이 무효 처리됐으면 attempt=stale-no-op으로 남긴다.
 *
 * 도는 간격: 방금 일감이 있었으면 pollInterval 뒤에 다시 돌고(대기 시간 초기화),
 * 빈 바퀴였으면 대기 시간을 두 배씩 늘리다가 maxIdleSleep에서 멈춘다.
 * 한 바퀴 안에서 난 예외는 WARN으로 잡아 루프를 계속 살린다.
 * 종료({@code loop.isShutdown()}) 후에는 다음 바퀴를 예약하지 않는다.
 * 알림 기능 스위치({@code pipeline.notify.enabled=false})가 꺼져 있으면 아예 돌지 않는다.
 *
 * 자동 재시도 중단(give-up) 경보: 아직 별도 모니터링(actuator)이 없어서, 이 루프가
 * {@code countGivenUp}을 주기적으로 조회하고 0보다 크면 ERROR 로그로 알린다.
 * 경보의 기준은 로그가 아니라 DB에서 센 이 카운트이고,
 * 이 폴링은 그 값을 주기적으로 읽어 주는 최소한의 연결(V1)이다.
 */
@Slf4j
@Component
public class NotifyScheduler {

    /** 자동 재시도 중단(give-up) 경보의 폴링 주기. 바퀴 간격과 무관하게 이 간격마다 한 번만 countGivenUp을 조회한다. */
    private static final Duration GIVE_UP_ALERT_POLL_INTERVAL = Duration.ofMinutes(5);

    private final NotifyClaimer notifyClaimer;
    private final SlackNotifier slackNotifier;
    private final NotifyWriteBack notifyWriteBack;
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
            NotifyRepository notifyRepository, NotifySettings settings, Clock clock) {
        this.notifyClaimer = notifyClaimer;
        this.slackNotifier = slackNotifier;
        this.notifyWriteBack = notifyWriteBack;
        this.notifyRepository = notifyRepository;
        this.settings = settings;
        this.clock = clock;
        this.idleBackoff = settings.backoffBase();
        this.nextGiveUpAlertPollAt = clock.instant();   // 첫 바퀴에서 즉시 1회 폴링한다
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
            if (!loop.isShutdown()) {   // @PreDestroy로 종료된 뒤에 재예약하면 RejectedExecutionException이 난다
                loop.schedule(this::runSweep, delay.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 한 건을 점유 → 전달 → 기록한다. 반환값은 "일감이 있었는가"다.
     * 점유할 행이 없으면 false를 돌려주고, 루프는 대기 시간을 늘린다.
     * 알림 기능이 켜져 있는지는 여기서 확인하지 않는다 — 꺼져 있으면 {@code start()}가 루프 자체를
     * 올리지 않아 이 메서드에 도달하지 않는다.
     * webhook 주소는 {@code settings.slackWebhookUrl()}을 그대로 쓴다 — 값의 유효성은
     * 부팅 시점 검증이 보장한다.
     * 전달이 실패하면 먼저 재시도 정보를 기록(onFailure)하고, 그 반환값을 attempt에 실어
     * WARN 구조화 로그(필수 필드: pipeline·status·attempt·sink·resp_class = 예외 클래스 이름)를 남긴다.
     * 기록이 로그보다 먼저인 이유는 로그에 올라간 시도 횟수를 정확히 싣기 위해서다.
     * 실패는 여기서 흡수하므로 한 바퀴가 죽지 않는다.
     * catch가 감싸는 범위는 전달 호출 하나뿐이다. 시도 횟수는 "실제로 호출했는데 실패했다"일 때만
     * 올려야 하기 때문이다. 그래서 전달이 성공한 뒤 onSuccess가 실패하면 이를 전달 실패로 기록하지 않고
     * runSweep으로 올린다 — 점유가 남아 있다가 만료되면 다른 서버가 다시 집어 재전달하므로,
     * 알림이 사라지는 대신 드물게 중복될 수 있다(최소 한 번은 전달한다는 방침).
     * 같은 이유로 onFailure 자체가 실패해도(DB 다운 등) runSweep의 루프 유지 WARN으로 올라간다.
     */
    boolean deliverOne() {
        Optional<NotifyClaim> claim = notifyClaimer.claimOne();
        if (claim.isEmpty()) {
            return false;
        }
        NotifyClaim claimed = claim.get();
        try {
            slackNotifier.deliver(settings.slackWebhookUrl(), claimed.payload());
        } catch (RuntimeException deliveryFailed) {   // harness-allow: targeted-catch — 전송 경계: 어떤 전달 실패든 여기서 잡아 재시도 기록·자동 재시도 중단으로 수렴시킨다. 더 큰 알림은 give-up 경보가 맡는다.
            OptionalInt attempts = notifyWriteBack.onFailure(claimed.pipelineId(), claimed.token());
            log.warn("notify delivery failed pipeline={} status={} attempt={} sink=slack resp_class={}",
                    claimed.pipelineId(), claimed.payload().terminalStatus(), attemptLabel(attempts),
                    deliveryFailed.getClass().getSimpleName(), deliveryFailed);
            return true;
        }
        notifyWriteBack.onSuccess(claimed.pipelineId(), claimed.token());
        return true;
    }

    /** 실패 WARN 로그의 attempt 필드 값이다. 기록이 무효 처리됐으면(다른 서버가 이미 처리한 행) 숫자 대신 stale-no-op으로 표시한다. */
    private static String attemptLabel(OptionalInt attempts) {
        return attempts.isPresent() ? Integer.toString(attempts.getAsInt()) : "stale-no-op";
    }

    /** 다음 바퀴까지의 대기 시간. 일감이 있었으면 pollInterval로 돌아가고(대기 시간 초기화), 빈 바퀴면 maxIdleSleep 상한까지 두 배씩 늘린다. */
    Duration nextDelay(boolean delivered) {
        if (delivered) {
            idleBackoff = settings.backoffBase();
            return settings.pollInterval();
        }
        idleBackoff = min(idleBackoff.multipliedBy(2), settings.maxIdleSleep());
        return idleBackoff;
    }

    /** 자동 재시도 중단 경보의 최소 구현이다. 폴링 주기가 지났으면 countGivenUp을 조회하고, 0보다 크면 ERROR 로그로 알린다. */
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
