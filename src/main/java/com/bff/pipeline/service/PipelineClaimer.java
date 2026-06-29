package com.bff.pipeline.service;

import com.bff.pipeline.ExecutionSettings;
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
 * ADR-021 tx1 클레임: due 파이프라인 하나를 원자적으로 클레임하고 리스 토큰을 발급한다(ADR Decision 2).
 * 소프트 어드미션 게이트({@code runningPipelineCap})를 먼저 확인한 뒤,
 * {@code FOR UPDATE SKIP LOCKED}(MySQL) / {@code FOR UPDATE}(H2) 스캔으로 파이프라인을 선택하고
 * 신규 UUID 펜싱 토큰을 stamping하여 커밋한다. 클레임 결과가 없어도 백로그가 비어있다는 의미가 아니다
 * (SKIP LOCKED가 다른 워커가 보유한 행을 건너뜀). 오버슈트는 소프트 게이트로 허용된다(ADR Decision 7).
 */
@Component
public class PipelineClaimer {

    public record Claim(long pipelineId, String token) {}

    private final PipelineRepository pipelines;
    private final ExecutionSettings settings;
    private final Clock clock;

    public PipelineClaimer(PipelineRepository pipelines, ExecutionSettings settings, Clock clock) {
        this.pipelines = pipelines;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Optional<Claim> claimOneDue() {
        Instant now = clock.instant();
        if (pipelines.countByClaimedUntilAfter(now) >= settings.runningPipelineCap()) {
            return Optional.empty();
        }
        List<Pipeline> due = pipelines.findClaimableDuePipelines(now, PageRequest.of(0, 1));
        if (due.isEmpty()) return Optional.empty();
        Pipeline pipeline = due.get(0);
        String token = UUID.randomUUID().toString();
        pipeline.setClaimedBy(token);
        pipeline.setClaimedUntil(now.plus(settings.leaseDuration()));
        return Optional.of(new Claim(pipeline.getId(), token));
    }
}
