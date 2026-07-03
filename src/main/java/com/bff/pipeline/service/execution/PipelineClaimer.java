package com.bff.pipeline.service.execution;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.repository.PipelineRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * claim 트랜잭션 — 외부 호출(run 단계) 직전에 처리할 pipeline 하나를 잡는다. admission soft-cap(Decision 7)을 통과하면
 * due pipeline 한 행을 원자적으로 claim하면서 fencing token과 lease를 찍는다(Decision 2). 이때 status도 RUNNING으로
 * 함께 써 PENDING(시작 지연 대기)을 RUNNING으로 전이시킨다(LIN-30). lease와 같은 UPDATE에 실려 원자적이라, claim
 * holder가 유일한 status writer라는 불변식이 유지되고 "live claim인데 아직 PENDING"인 창(취소 유실 위험)이 생기지
 * 않는다. 이미 RUNNING이면 값 no-op이다. next_due_at은 여기서 건드리지 않는다 — 전진은 report 트랜잭션(tx2)의 몫이다.
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
                    pipeline.setStatus(PipelineStatus.RUNNING);   // LIN-30: PENDING→RUNNING을 lease와 같은 tx1 UPDATE에 실어 원자 전이(이미 RUNNING이면 no-op)
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
