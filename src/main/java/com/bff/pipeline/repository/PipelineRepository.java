package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * {@link Pipeline} 행의 영속성 계층이다. ADR-021 실행 모델의 claim/guard/cancel 질의를 담는다.
 *
 * <p>종료 전이는 별도 CAS가 아니라 write-back 트랜잭션의 {@code FOR UPDATE} 잠금 아래에서 엔티티 setter로 처리한다
 * ({@code StepReporter.terminalize}/{@code cancelIfIdle}). RUNNING 가드는 그 질의들이 직접 들고 있다.
 *
 * <p><b>ADR-021 claim(claim 트랜잭션)</b>: {@code findNextClaimableDuePipeline}은 due 조건을 만족하는 행 하나를
 * {@code PESSIMISTIC_WRITE} + lock-timeout {@code -2}로 잠근다({@code lockClaimableDuePipelines}에 Limit 1로 위임). 이 힌트는 MySQL 8에서 {@code FOR UPDATE SKIP LOCKED}로
 * 렌더링되고 H2에서는 무시돼 일반 {@code FOR UPDATE}가 된다. 덕분에 두 워커가 같은 행을 노려도 블로킹 없이 서로 다른
 * 행을 나눠 claim한다. <b>guarded write-back(write-back 트랜잭션)</b>: {@code findByIdForUpdate}로 pipeline 행을 잠근 뒤 호출자가
 * {@code claimed_by} 소유권을 검증한다. <b>cancel</b>: {@code cancelIfIdle}은 Case A로, claim이 없거나 만료됐으면
 * 즉시 종료하고 claim을 지운다. {@code requestCancel}은 Case B로, 플래그만 세우고 워커를 깨운다.
 * {@code countByClaimedUntilAfter}는 admission soft-cap 카운트에, {@code findNearestClaimableDueAt}은
 * idle-sleep 상한 계산에 쓴다. claim 술어와 {@code cancelIfIdle}(Case A), {@code findNearestClaimableDueAt}은
 * RUNNING과 PENDING(시작 지연 대기)을 함께 본다 — PENDING은 지연 경과 후 claim되고 그 트랜잭션에서 RUNNING으로
 * 전이한다(LIN-30). {@code requestCancel}(Case B)은 live-lease 전용이라 RUNNING만 본다(PENDING은 미claim이라 항상
 * Case A로 취소됨).
 */
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    /**
     * claim 트랜잭션의 진입 질의 — claim 가능한 due pipeline <b>하나</b>를 잠가서 가져온다(없으면 empty).
     * JPQL엔 {@code LIMIT} 문법이 없어 개수 제한을 {@link Limit}로 넘기고 그 결과(최대 1건)를 단건으로 좁힌다.
     * 잠금·SKIP LOCKED 렌더링은 {@link #lockClaimableDuePipelines}가 담당한다.
     */
    default Optional<Pipeline> findNextClaimableDuePipeline(Instant now) {
        return lockClaimableDuePipelines(now, Limit.of(1)).stream().findFirst();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select p from Pipeline p where p.status in "
            + "(com.bff.pipeline.enums.PipelineStatus.RUNNING, com.bff.pipeline.enums.PipelineStatus.PENDING) "
            + "and p.nextDueAt <= :now and (p.claimedUntil is null or p.claimedUntil < :now) "
            + "order by p.nextDueAt")
    List<Pipeline> lockClaimableDuePipelines(@Param("now") Instant now, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pipeline p where p.id = :id")
    Optional<Pipeline> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.status = com.bff.pipeline.enums.PipelineStatus.CANCELLED, "
            + "p.activeTarget = null, p.claimedBy = null, p.claimedUntil = null, p.lastActivityAt = :now "
            + "where p.id = :id and p.status in "
            + "(com.bff.pipeline.enums.PipelineStatus.RUNNING, com.bff.pipeline.enums.PipelineStatus.PENDING) "
            + "and (p.claimedBy is null or p.claimedUntil < :now)")
    int cancelIfIdle(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.cancelRequested = true, p.nextDueAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING")
    int requestCancel(@Param("id") Long id, @Param("now") Instant now);

    int countByClaimedUntilAfter(Instant now);

    @Query("select min(p.nextDueAt) from Pipeline p where p.status in "
            + "(com.bff.pipeline.enums.PipelineStatus.RUNNING, com.bff.pipeline.enums.PipelineStatus.PENDING) "
            + "and (p.claimedUntil is null or p.claimedUntil < :now)")
    Optional<Instant> findNearestClaimableDueAt(@Param("now") Instant now);

    // ── Admin 조회 API(P1~P8) 읽기 질의. 실행 경로와 무관한 대시보드/이력용이다. ──

    /** 실시간 현황(P1): status별 순간 개수. */
    long countByStatus(PipelineStatus status);

    /** 기간 통계(P2): createdAt이 기간 하한 이후인 pipeline을 status별로 집계한다. */
    @Query("select p.status as status, count(p) as count from Pipeline p "
            + "where p.createdAt >= :since group by p.status")
    List<PipelineStatusCount> countByStatusSince(@Param("since") Instant since);

    /** 대시보드 목록(P3): status/provider/기간(createdAt) 선택 필터 + 페이지네이션. null 인자는 해당 필터를 건너뛴다. */
    @Query(value = "select p from Pipeline p where "
            + "(:status is null or p.status = :status) and "
            + "(:provider is null or p.cloudProvider = :provider) and "
            + "(:since is null or p.createdAt >= :since)",
            countQuery = "select count(p) from Pipeline p where "
            + "(:status is null or p.status = :status) and "
            + "(:provider is null or p.cloudProvider = :provider) and "
            + "(:since is null or p.createdAt >= :since)")
    Page<Pipeline> search(@Param("status") PipelineStatus status,
            @Param("provider") CloudProvider provider,
            @Param("since") Instant since,
            Pageable pageable);

    /** 대상 이력 목록(P7): 특정 target의 실행. 정렬은 호출측 Pageable(기본 created_at desc, id desc 결정적 순서)이 정한다. */
    Page<Pipeline> findByTarget(String target, Pageable pageable);

    /** 최근 파이프라인 카드(P8): 특정 target의 가장 최근 실행 1건(상태 무관). id를 tiebreaker로 결정적 선택. */
    Optional<Pipeline> findFirstByTargetOrderByCreatedAtDescIdDesc(String target);
}
