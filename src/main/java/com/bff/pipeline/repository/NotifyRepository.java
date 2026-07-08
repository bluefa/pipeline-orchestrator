package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Pipeline;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * ADR-022 종단 상태 알림의 claim·지표 질의 계층이다. 실행 claim({@link PipelineRepository})과 같은
 * {@link Pipeline} 행을 보되 술어가 다른 별도 work-kind다 — 종단 3상태(DONE/FAILED/CANCELLED)이면서
 * 미알림({@code notified_at is null})이고, give-up 임계({@code notify_attempts < maxAttempts}) 아래이며,
 * backoff 게이트({@code notify_next_at})와 notify lease({@code notify_claimed_until}) 게이트가 열린 행만 집는다.
 *
 * claim은 {@code PipelineRepository}의 claim 패턴을 그대로 따른다 — {@code PESSIMISTIC_WRITE} +
 * lock-timeout {@code -2} 힌트는 MySQL 8에서 {@code FOR UPDATE SKIP LOCKED}로 렌더링되고 H2에서는 무시된다.
 * write-back 트랜잭션의 행 잠금은 기존 {@link PipelineRepository#findByIdForUpdate}를 재사용하므로 여기에
 * 중복 정의하지 않는다. {@code :now}는 주입된 앱 Clock 시각이다(DB now()와 섞지 않는다 — lease 만료·재시도
 * due 판정이 한 시계 기준이어야 한다).
 */
public interface NotifyRepository extends JpaRepository<Pipeline, Long> {

    /**
     * claim 트랜잭션의 진입 질의 — 알림 가능한 종단·미알림 행 하나를 SKIP LOCKED로 잠가 가져온다(없으면 empty).
     * JPQL엔 LIMIT 문법이 없어 개수 제한을 {@link Limit}로 넘기고 그 결과(최대 1건)를 단건으로 좁힌다.
     */
    default Optional<Pipeline> findNextNotifiable(Instant now, int maxAttempts) {
        return lockNotifiable(now, maxAttempts, Limit.of(1)).stream().findFirst();
    }

    /**
     * 알림 가능 행 잠금 질의. {@code notifyAttempts < :maxAttempts}가 give-up 행의 재클레임을 막는 1차
     * 안전장치다(far-future {@code notify_next_at} 값에 의존하지 않는다). 정렬은 {@code notify_next_at} 오름차순
     * (NULL 선두 — 신규 종단이 먼저) + {@code id} 오름차순의 결정적 tie-break다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select p from Pipeline p "
            + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
            + "com.bff.pipeline.enums.PipelineStatus.FAILED, "
            + "com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
            + "and p.notifiedAt is null "
            + "and p.notifyAttempts < :maxAttempts "
            + "and (p.notifyNextAt is null or p.notifyNextAt <= :now) "
            + "and (p.notifyClaimedUntil is null or p.notifyClaimedUntil < :now) "
            + "order by p.notifyNextAt asc, p.id asc")
    List<Pipeline> lockNotifiable(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Limit limit);

    /**
     * 지표 1: terminal_notification_backlog_age — 비활성 채널 backlog까지 포함한 총 미알림 age의 기준 시각.
     * min(lastActivityAt)을 종단 시각으로 쓰는 건, 종단 행은 terminalize 후 다시 쓰이지 않아(ADR-021 불변식)
     * lastActivityAt == 종단 시각이 되기 때문이다(그때만 유효). give-up 행({@code notifyAttempts >= maxAttempts})은
     * notifiedAt이 영원히 null이라 age를 무한 오염하므로 제외한다(ADR-022 §4) — give-up은 별도로
     * {@link #countGivenUp}으로 감시한다.
     */
    @Query("select min(p.lastActivityAt) from Pipeline p "
            + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
            + "com.bff.pipeline.enums.PipelineStatus.FAILED, "
            + "com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
            + "and p.notifiedAt is null "
            + "and p.notifyAttempts < :maxAttempts")
    Optional<Instant> oldestUnnotifiedAt(@Param("maxAttempts") int maxAttempts);

    /**
     * 지표 2: notify_delivery_pending_age — "전달 정체" age의 기준 시각. backlog과 달리 due 행만 본다
     * (미도래 backoff 행 제외) — 건강한 재시도 대기를 전달 막힘으로 오인하지 않게 한다. 이 쿼리를 V1부터 쓰고,
     * 경보는 활성 채널일 때만 평가한다(비활성 시 suppress; ADR-022 §4).
     */
    @Query("select min(p.lastActivityAt) from Pipeline p "
            + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
            + "com.bff.pipeline.enums.PipelineStatus.FAILED, "
            + "com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
            + "and p.notifiedAt is null "
            + "and p.notifyAttempts < :maxAttempts "
            + "and (p.notifyNextAt is null or p.notifyNextAt <= :now)")
    Optional<Instant> oldestDeliveryPending(@Param("maxAttempts") int maxAttempts, @Param("now") Instant now);

    /**
     * give-up 행 수 — 사람 개입 필요 신호(ADR-022 §4 필수 경보의 정규 소스). maxAttempts 도달 후에도
     * notifiedAt이 null인 종단 행을 센다.
     */
    @Query("select count(p) from Pipeline p "
            + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
            + "com.bff.pipeline.enums.PipelineStatus.FAILED, "
            + "com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
            + "and p.notifiedAt is null "
            + "and p.notifyAttempts >= :maxAttempts")
    long countGivenUp(@Param("maxAttempts") int maxAttempts);
}
