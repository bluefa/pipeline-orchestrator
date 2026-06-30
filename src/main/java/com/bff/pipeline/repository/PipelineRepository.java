package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Pipeline;
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
 * {@link Pipeline} 행(row)의 영속성 계층이다. {@code findByActiveTarget}은 해당 target의 현재 활성 실행(run)을
 * 반환한다(없으면 빈 값): 불변식에 따라 실행이 비종료(non-terminal) 상태인 동안에만 정확히
 * {@code active_target == target}이 성립하며(종료 시 NULL로 초기화됨), {@code uq_pipeline_active_target}
 * 유일 인덱스를 활용하여 최대 하나의 행을 반환한다. 이 메서드는 중복 생성(duplicate-create) 유일 위반(unique violation)
 * 이후 기존 실행을 복구하는 데 사용된다.
 *
 * <p><b>클레임/리스/취소/어드미션 쿼리 (ADR-021, S2):</b>
 * {@code findClaimableDuePipelines}는 tx1 클레임 스캔에 사용된다 — RUNNING 상태이고 {@code nextDueAt}이 만료되었으며
 * 클레임이 없거나 리스가 만료된 pipeline 행을 비관적 쓰기 락(pessimistic write lock)으로 조회한다. Hibernate의
 * {@code jakarta.persistence.lock.timeout = -2} 힌트({@code LockOptions.SKIP_LOCKED})는 MySQL 8에서
 * {@code FOR UPDATE SKIP LOCKED}로 렌더링되어 동시 워커 간 클레임 충돌을 방지한다. H2(테스트 환경)에서는
 * {@code SKIP LOCKED} 힌트가 무시되어 단순 {@code FOR UPDATE}로 동작하며, 단일 스레드 테스트에서는 동작에 영향이
 * 없다(실제 다중 워커 동시성은 MySQL CI에서만 검증 가능하다). 호출자는 {@code Pageable.ofSize(1)}로 결과를 1건으로
 * 제한한다.
 * {@code findByIdForUpdate}는 tx2 가드 라이트백(guarded write-back)에서 pipeline 행을 비관적 쓰기 락으로 조회하는
 * 데 사용된다.
 * {@code cancelIfIdle}은 취소 Case A(클레임 없음 또는 리스 만료 상태)에 해당하며, RUNNING 상태이고 클레임이 없는
 * pipeline을 즉시 CANCELLED로 전환하고 클레임을 초기화한다(ADR-021 Decision 6, A1).
 * {@code requestCancel}은 취소 Case B(활성 리스 보유 중)에 해당하며, 협력적 취소 플래그를 설정하고
 * {@code nextDueAt}을 현재 시각으로 당겨 워커가 즉시 플래그를 인식하도록 한다(ADR-021 Decision 6, Case B).
 * {@code countByClaimedUntilAfter}는 어드미션 소프트 게이트(runningPipelineCap)를 위해 현재 활성 클레임
 * (리스 미만료) 수를 반환한다(ADR-021 Decision 7).
 * {@code findNearestClaimableDueAt}은 ADR-021 §280 DB-폴링 부하 제어를 위해 RUNNING 상태이고
 * 클레임이 없거나 리스가 만료된 파이프라인 중 가장 이른 {@code nextDueAt}을 반환한다. 결과가 없으면
 * 빈 Optional을 반환한다. 스케줄러가 유휴 슬립을 이 값으로 단축(cap)하여 과도한 폴링 없이
 * due 파이프라인이 생기는 즉시 깨어날 수 있도록 한다({@code idx_pipeline_claim} 인덱스 활용).
 */
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    Optional<Pipeline> findByActiveTarget(String activeTarget);

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
