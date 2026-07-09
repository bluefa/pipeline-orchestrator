package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.repository.PipelineRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 전송(트랜잭션 밖에서 일어나는 Slack 호출)의 결과를 DB에 기록하는 트랜잭션이다.
 * 성공이면 {@code notified_at}에 시각을 적어 그 파이프라인을 알림 대상에서 영구히 빼고,
 * 점유 정보와 남아 있던 재시도 예약({@code notify_next_at})을 지운다.
 * 실패면 시도 횟수({@code notify_attempts})를 1 올리고 다음 재시도 시각을 정한다.
 * 실패가 반복될수록 재시도 간격이 두 배씩 늘어난다(상한 있음, 무작위 오차 포함).
 * 횟수가 {@code maxAttempts}에 닿으면 자동 재시도를 중단한다 — {@code notify_next_at}을 먼 미래로 밀고
 * ERROR 로그를 남긴다. 이때 먼 미래 값은 눈에 보이는 표시일 뿐이고,
 * 그 행이 다시 잡히지 않게 막는 실제 장치는 조회 조건의 시도 횟수 비교다.
 * 성공이든 실패든 점유는 해제한다.
 * {@code onFailure}는 올린 뒤의 시도 횟수를 돌려주고, 기록이 무효 처리됐으면 empty를 돌려준다.
 * 호출자({@code NotifyScheduler})가 실패 WARN 로그의 attempt 필드를 이 값으로 채운다.
 *
 * 성공 기록과 실패 기록 모두 같은 확인 절차({@link #ownsLiveNotifyClaim})를 거친다.
 * 기존 {@link PipelineRepository#findByIdForUpdate}로 행을 잠근 뒤, 발급받았던 점유 확인용 토큰이
 * 행의 것과 일치하고 아직 알림이 나가지 않은 상태({@code notified_at}이 null)일 때만 기록한다.
 * 이 확인이 막는 사고: 점유가 만료된 뒤 뒤늦게 돌아온 서버가 실패를 기록하려 할 때,
 * 그 사이 다른 서버가 이미 성공시킨 행의 시도 횟수와 재시도 예약을 망가뜨리는 것.
 * 확인에 걸리면 아무것도 바꾸지 않는다.
 *
 * 감사용 구조화 로그: 성공 INFO와 자동 재시도 중단 ERROR는 여기서 남기고,
 * 둘 다 pipeline_id·terminal_status·attempt·sink를 싣는다.
 * 실패 WARN은 예외를 쥐고 있는 호출자({@code NotifyScheduler})가 응답 분류와 함께 남긴다.
 * 자세한 배경은 ADR-022 참조.
 */
@Slf4j
@Component
public class NotifyWriteBack {

    /**
     * 자동 재시도 중단을 표시하는 먼 미래 오프셋. 그 행이 다시 잡히지 않게 막는 근거가 아니라
     * 정렬과 눈에 띄기 위한 표시일 뿐이다(테스트가 참조한다).
     */
    public static final Duration GIVE_UP_FAR_FUTURE = Duration.ofDays(3650);
    /** 재시도 간격을 두 배씩 늘릴 때 지수의 상한. long 오버플로를 막는다(이 지수쯤 되면 어차피 backoffMax에 잘린다). */
    private static final int MAX_BACKOFF_SHIFT = 20;

    private final PipelineRepository pipelineRepository;
    private final NotifySettings settings;
    private final Clock clock;

    public NotifyWriteBack(PipelineRepository pipelineRepository, NotifySettings settings, Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public void onSuccess(long pipelineId, String token) {
        pipelineRepository.findByIdForUpdate(pipelineId)
                .filter(pipeline -> ownsLiveNotifyClaim(pipeline, token))
                .ifPresent(pipeline -> {
                    log.info("notify delivered pipeline={} status={} attempt={} sink=slack resp_class=2xx",
                            pipelineId, pipeline.getStatus(), pipeline.getNotifyAttempts() + 1);
                    pipeline.setNotifiedAt(clock.instant());
                    pipeline.setNotifyClaimedBy(null);
                    pipeline.setNotifyClaimedUntil(null);
                    pipeline.setNotifyNextAt(null);   // 남아 있던 재시도 예약을 지운다(나중에 원인 분석할 때 헷갈리지 않게)
                });
    }

    /**
     * 전송 실패를 기록한다. 시도 횟수를 1 올리고, 올린 뒤의 값을 돌려준다.
     * 점유 확인에 걸린 뒤늦은 기록이면 아무것도 바꾸지 않고 empty를 돌려준다 —
     * 호출자는 이를 attempt=stale-no-op으로 로깅한다.
     */
    @Transactional
    public OptionalInt onFailure(long pipelineId, String token) {
        Optional<Pipeline> ownedRow = pipelineRepository.findByIdForUpdate(pipelineId)
                .filter(pipeline -> ownsLiveNotifyClaim(pipeline, token));
        if (ownedRow.isEmpty()) {
            return OptionalInt.empty();
        }
        Pipeline pipeline = ownedRow.get();
        int attempts = pipeline.getNotifyAttempts() + 1;
        pipeline.setNotifyAttempts(attempts);
        if (attempts >= settings.maxAttempts()) {
            pipeline.setNotifyNextAt(clock.instant().plus(GIVE_UP_FAR_FUTURE));
            log.error("notify give-up pipeline={} status={} after {} attempts sink=slack",
                    pipelineId, pipeline.getStatus(), attempts);
        } else {
            pipeline.setNotifyNextAt(clock.instant().plus(backoff(attempts)));
        }
        pipeline.setNotifyClaimedBy(null);
        pipeline.setNotifyClaimedUntil(null);
        return OptionalInt.of(attempts);
    }

    /**
     * 결과를 기록해도 되는지 확인하는 조건이다. 토큰이 행의 {@code notifyClaimedBy}와 일치하고,
     * 아직 알림이 나가지 않은 상태({@code notified_at}이 null)일 때만 기록을 허용한다.
     * 토큰이 다르면 점유가 만료된 뒤 다른 서버가 새로 점유한 것이다.
     * {@code notified_at}이 차 있으면 다른 서버가 이미 성공시킨 것이다.
     * 두 경우 모두 아무것도 기록하지 않는다.
     * 성공 기록과 실패 기록 모두 findByIdForUpdate로 행을 잠근 상태에서 이 확인을 거친다.
     */
    private static boolean ownsLiveNotifyClaim(Pipeline pipeline, String token) {
        return token.equals(pipeline.getNotifyClaimedBy()) && pipeline.getNotifiedAt() == null;
    }

    /** 다음 재시도까지 기다릴 시간. 실패할수록 두 배씩 늘고 상한에서 멈추며, 무작위 오차(jitter)를 더한다. attempts는 이미 1 올린 값(1부터)이다. */
    private Duration backoff(int attempts) {
        long baseMillis = settings.backoffBase().toMillis() * (1L << Math.min(attempts - 1, MAX_BACKOFF_SHIFT));
        long cappedMillis = Math.min(baseMillis, settings.backoffMax().toMillis());
        double jitterFraction = ThreadLocalRandom.current().nextDouble(-1.0, 1.0) * settings.jitterRatio();
        return Duration.ofMillis(Math.max(1L, Math.round(cappedMillis * (1.0 + jitterFraction))));
    }
}
