package com.bff.pipeline.service.execution;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.repository.PipelineRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-021 phase-A 직전의 tx1: 외부 시스템을 건드리기 전에 due pipeline 하나를 원자적으로 claim하고
 * fencing token + lease를 스탬프한다(Decision 2). admission soft-cap(Decision 7)을 먼저 적용한다.
 *
 * <p>claim은 {@code FOR UPDATE SKIP LOCKED}(MySQL)로 한 행을 잠그므로 두 워커가 같은 스캔을 경쟁해도
 * 블로킹 없이 서로 다른 행을 가져간다. {@code claimed_by}는 매번 새 UUID라 lease 만료 후 재claim 시
 * 다른 토큰이 부여되어, 이전 claim의 in-flight tx2가 소유권 가드에서 no-op된다(stale-straggler fencing).
 *
 * <p><b>빈 결과 ≠ backlog empty</b>: SKIP LOCKED는 다른 워커가 잡은 행을 조용히 건너뛰므로, 빈 claim을
 * 유휴 신호로 해석하지 않는다(스케줄러의 backoff가 폴링 케이던스를 담당).
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
        // soft admission gate — 활성 claim 수만 센다(전체 RUNNING 아님). count-read라 M+C-1 overshoot 허용.
        if (pipelineRepository.countByClaimedUntilAfter(now) >= executionSettings.runningPipelineCap()) {
            return Optional.empty();
        }
        List<Pipeline> due = pipelineRepository.findClaimableDuePipelines(now, PageRequest.of(0, 1));
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Pipeline pipeline = due.get(0);
        String token = UUID.randomUUID().toString();
        pipeline.setClaimedBy(token);
        pipeline.setClaimedUntil(now.plus(executionSettings.leaseDuration()));
        return Optional.of(new Claim(pipeline.getId(), token));
    }

    @Transactional(readOnly = true)
    public Optional<Instant> nearestClaimableDueAt() {
        return pipelineRepository.findNearestClaimableDueAt(clock.instant());
    }
}
