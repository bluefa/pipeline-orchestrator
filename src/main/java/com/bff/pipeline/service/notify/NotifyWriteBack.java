package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.repository.PipelineRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * notify write-back 트랜잭션 — 전달(외부 호출, 트랜잭션 밖)의 결과를 guarded로 기록한다(ADR-022 §2).
 * 성공이면 {@code notified_at}을 스탬프하고 lease와 stale backoff 메타데이터({@code notify_next_at})를
 * 비운다. 실패면 attempts를 post-increment로 올리고, {@code maxAttempts} 도달 시 give-up(far-future
 * {@code notify_next_at}은 보조 표시일 뿐 — 재클레임 배제는 claim 술어의 attempts 비교가 담당한다) +
 * ERROR 로그, 아니면 상한 있는 지수 backoff(jitter 포함)로 다음 재시도 시각을 민다. 어느 쪽이든 lease는 해제한다.
 *
 * 두 경로 모두 같은 fencing 가드를 탄다 — 기존 {@link PipelineRepository#findByIdForUpdate}로 행을 잠근 뒤
 * token 일치 + 아직 미알림({@code notified_at is null})일 때만 적용한다. lease 만료 뒤 되살아난 stale
 * worker의 실패 write-back이, 그 사이 다른 worker가 성공시켜 이미 notified_at이 찍힌 행의 attempts/backoff를
 * 오염시키는 것을 막는다(0행 매칭과 동형의 no-op).
 *
 * 구조화 전달 로그(ADR-022 §결과 감사 보조): 성공 INFO / give-up ERROR 모두 pipeline_id·terminal_status·
 * attempt·sink를 싣는다. 실패 WARN(응답 분류 포함)은 예외를 쥔 호출자({@code NotifyScheduler})가 남긴다.
 */
@Slf4j
@Component
public class NotifyWriteBack {

    /** give-up 보조 표시용 far-future 오프셋. 재클레임 배제의 근거가 아니라 정렬/가시성용이다(테스트가 참조). */
    public static final Duration GIVE_UP_FAR_FUTURE = Duration.ofDays(3650);
    /** 지수 backoff 시프트 상한 — long 오버플로 방지(이 지수면 어차피 backoffMax에 잘린다). */
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
        guarded(pipelineId, token, pipeline -> {
            log.info("notify delivered pipeline={} status={} attempt={} sink=slack resp_class=2xx",
                    pipelineId, pipeline.getStatus(), pipeline.getNotifyAttempts() + 1);
            pipeline.setNotifiedAt(clock.instant());
            pipeline.setNotifyClaimedBy(null);
            pipeline.setNotifyClaimedUntil(null);
            pipeline.setNotifyNextAt(null);   // stale backoff 메타데이터 제거(postmortem 혼선 방지)
        });
    }

    @Transactional
    public void onFailure(long pipelineId, String token) {
        guarded(pipelineId, token, pipeline -> {
            int attempts = pipeline.getNotifyAttempts() + 1;   // post-increment 기준(ADR-022 §2)
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
        });
    }

    /**
     * {@code findByIdForUpdate}로 잠그고 token 일치 + 아직 미알림일 때만 apply한다(stale-straggler fencing).
     * 토큰 불일치 = lease 만료 후 재claim, notified_at non-null = 다른 worker가 이미 성공 — 둘 다 no-op.
     * 성공·실패 write-back이 모두 이 가드를 탄다.
     */
    private void guarded(long pipelineId, String token, Consumer<Pipeline> mutate) {
        pipelineRepository.findByIdForUpdate(pipelineId).ifPresent(pipeline -> {
            if (token.equals(pipeline.getNotifyClaimedBy()) && pipeline.getNotifiedAt() == null) {
                mutate.accept(pipeline);
            }
        });
    }

    /** 상한 있는 지수 backoff + jitter. attempts는 post-increment된 값(1부터)이다. */
    private Duration backoff(int attempts) {
        long baseMillis = settings.backoffBase().toMillis() * (1L << Math.min(attempts - 1, MAX_BACKOFF_SHIFT));
        long cappedMillis = Math.min(baseMillis, settings.backoffMax().toMillis());
        double jitterFraction = ThreadLocalRandom.current().nextDouble(-1.0, 1.0) * settings.jitterRatio();
        return Duration.ofMillis(Math.max(1L, Math.round(cappedMillis * (1.0 + jitterFraction))));
    }
}
