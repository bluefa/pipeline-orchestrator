package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * {@link Pipeline} 행(row)의 영속성 계층이다. ADR-016 도메인 질의({@code findByActiveTarget}, {@code finish})에
 * ADR-021 실행 모델의 claim/guard/cancel 질의를 더한다.
 *
 * <p>{@code findByActiveTarget}은 해당 target의 현재 활성 실행을 반환한다(불변식상 비종료 동안에만
 * {@code active_target == target}). {@code finish}는 RUNNING-가드 CAS로 pipeline을 종료시키고
 * {@code active_target}을 초기화한다.
 *
 * <p><b>ADR-021 claim(tx1)</b>: {@code findClaimableDuePipelines}는 due predicate를 만족하는 행을
 * {@code PESSIMISTIC_WRITE} + lock-timeout {@code -2}(MySQL 8에서 {@code FOR UPDATE SKIP LOCKED}로 렌더링;
 * H2에서는 무시되어 일반 {@code FOR UPDATE})로 한 행 잠근다 — 두 워커가 같은 행을 경쟁해도 블로킹 없이
 * 서로 다른 행을 claim한다. <b>guarded write-back(tx2)</b>: {@code findByIdForUpdate}로 pipeline 행을
 * 잠근 뒤 호출자가 {@code claimed_by} 소유권을 검증한다. <b>cancel</b>: {@code cancelIfIdle}(Case A,
 * claim null/만료일 때 즉시 종료 + claim clear), {@code requestCancel}(Case B, 플래그만 set + wake).
 * {@code countByClaimedUntilAfter}는 admission soft-cap 카운트, {@code findNearestClaimableDueAt}은
 * idle-sleep 상한 계산에 쓰인다.
 */
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    Optional<Pipeline> findByActiveTarget(String activeTarget);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.status = :to, p.activeTarget = null, p.lastActivityAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING")
    int finish(@Param("id") Long id, @Param("to") PipelineStatus to, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select p from Pipeline p where p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING "
            + "and p.nextDueAt <= :now and (p.claimedUntil is null or p.claimedUntil < :now) "
            + "order by p.nextDueAt")
    List<Pipeline> findClaimableDuePipelines(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pipeline p where p.id = :id")
    Optional<Pipeline> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.status = com.bff.pipeline.enums.PipelineStatus.CANCELLED, "
            + "p.activeTarget = null, p.claimedBy = null, p.claimedUntil = null, p.lastActivityAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING "
            + "and (p.claimedBy is null or p.claimedUntil < :now)")
    int cancelIfIdle(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.cancelRequested = true, p.nextDueAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING")
    int requestCancel(@Param("id") Long id, @Param("now") Instant now);

    int countByClaimedUntilAfter(Instant now);

    @Query("select min(p.nextDueAt) from Pipeline p where p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING "
            + "and (p.claimedUntil is null or p.claimedUntil < :now)")
    Optional<Instant> findNearestClaimableDueAt(@Param("now") Instant now);
}
