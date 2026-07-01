package com.bff.pipeline.service.execution;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.repository.PipelineRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * claim 트랜잭션 — 외부 호출(run 단계) 직전에 처리할 pipeline 하나를 잡는다. admission soft-cap(Decision 7)을 통과하면
 * due pipeline 한 행을 원자적으로 claim하면서 fencing token과 lease를 찍는다(Decision 2).
 *
 * <p>claim은 {@code FOR UPDATE SKIP LOCKED}(MySQL)로 한 행만 잠근다. 덕분에 여러 워커가 같은 스캔을
 * 경쟁해도 블로킹 없이 서로 다른 행을 나눠 갖는다. {@code claimed_by}에는 매번 새 UUID가 들어가므로,
 * lease가 만료돼 다른 워커가 재claim하면 토큰이 바뀐다. 그러면 뒤늦게 도착한 이전 claim의 write-back 트랜잭션은
 * 소유권 가드에서 걸러져 no-op된다(stale-straggler fencing).
 *
 * <p><b>빈 결과 ≠ backlog empty</b>: SKIP LOCKED는 다른 워커가 잡은 행을 조용히 건너뛴다. 따라서 빈 claim을
 * 유휴 신호로 읽지 않는다 — 폴링 케이던스는 스케줄러의 backoff가 맡는다.
 */
@Component
public class PipelineClaimer {

    private final PipelineRepository pipelineRepository;
    private final ExecutionSettings executionSettings;
    private final Clock clock;

    public PipelineClaimer(PipelineRepository pipelineRepository, ExecutionSettings executionSettings, Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.executionSettings = executionSettings;
        this.clock = clock;
    }

    @Transactional
    public Optional<Claim> claimOneDue() {
        Instant now = clock.instant();
        if (atRunningCapacity(now)) {
            return Optional.empty();
        }
        return pipelineRepository.findNextClaimableDuePipeline(now)
                .map(pipeline -> {
                    String token = UUID.randomUUID().toString();
                    pipeline.setClaimedBy(token);
                    pipeline.setClaimedUntil(now.plus(executionSettings.leaseDuration()));
                    return new Claim(pipeline.getId(), token);
                });
    }

    @Transactional(readOnly = true)
    public Optional<Instant> nearestClaimableDueAt() {
        return pipelineRepository.findNearestClaimableDueAt(clock.instant());
    }

    /**
     * admission soft-cap — 현재 활성(미만료) claim 수가 정원({@code runningPipelineCap})에 도달했는지 본다.
     * RUNNING 행 전체가 아니라 활성 claim만 센다. 단순 count-read라 M+C-1 overshoot는 허용한다(soft cap).
     */
    private boolean atRunningCapacity(Instant now) {
        return pipelineRepository.countByClaimedUntilAfter(now) >= executionSettings.runningPipelineCap();
    }
}
