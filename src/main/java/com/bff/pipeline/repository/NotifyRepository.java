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
 * 알림을 보내야 할 파이프라인을 찾아 오는 조회 계층이다. 실행용 조회({@link PipelineRepository})와 같은
 * {@link Pipeline} 테이블을 보지만, 행을 고르는 조건이 다르다. 알림 대상이 되려면 다음을 전부 만족해야 한다.
 * - 파이프라인이 끝난 상태다(DONE, FAILED, CANCELLED 중 하나).
 * - 아직 알림을 보낸 적이 없다({@code notified_at}이 비어 있다).
 * - 알림 기능 도입 시각 이후에 끝났다({@code last_activity_at >= enabledAfter}). 도입 전에 끝난
 *   옛 파이프라인은 알림 대상이 아니다.
 * - 전송 실패 횟수가 상한 아래다({@code notify_attempts < maxAttempts}). 상한에 닿은 행은
 *   자동 재시도를 멈추고 사람 개입을 기다린다.
 * - 다음 재시도 시각({@code notify_next_at})이 아직 없거나 이미 지났다.
 * - 다른 서버가 점유 중이 아니다({@code notify_claimed_until}이 비었거나 이미 지났다).
 *
 * 잠금 방식은 {@code PipelineRepository}의 실행용 조회와 같다. {@code PESSIMISTIC_WRITE}에
 * lock-timeout {@code -2} 힌트를 더하면 MySQL 8에서는 {@code FOR UPDATE SKIP LOCKED}로 실행되고
 * (다른 서버가 이미 잠근 행은 기다리지 않고 건너뛴다), H2에서는 힌트가 무시된다.
 * 전송 결과를 기록하는 트랜잭션의 행 잠금은 기존 {@link PipelineRepository#findByIdForUpdate}를
 * 그대로 쓰므로 여기에 다시 만들지 않는다. {@code :now}에는 애플리케이션에 주입된 Clock의 현재 시각을
 * 넣는다. DB의 now()와 섞어 쓰지 않는다 — 점유 만료 판정과 재시도 시각 판정이 같은 시계를 기준으로
 * 이루어져야 하기 때문이다.
 *
 * 구현 명세(§7)와 다른 점 하나: 명세에 있는 두 age 지표 쿼리(oldestUnnotifiedAt/oldestDeliveryPending)는
 * 만들지 않았다. 그 지표를 보여줄 화면(admin health/actuator)이 오너 결정(2026-07-09)으로 빠지면서
 * 지금 만들면 쓰이지 않는 코드가 되기 때문이고, actuator를 도입할 때 다시 추가한다.
 * 반면 {@link #countGivenUp}은 스케줄러가 주기적으로 읽는 경보 소스라 남겼다.
 * 자세한 배경은 ADR-022 참조.
 */
public interface NotifyRepository extends JpaRepository<Pipeline, Long> {

    /**
     * 알림을 보낼 파이프라인 하나를 잠가서 가져온다. 대상이 없으면 empty를 돌려준다.
     * 점유 트랜잭션({@code NotifyClaimer})이 가장 먼저 부르는 조회다.
     * JPQL에는 LIMIT 문법이 없어서 개수 제한을 {@link Limit}로 넘기고,
     * 그 결과(최대 1건)를 단건 Optional로 좁힌다.
     */
    default Optional<Pipeline> findNextNotifiable(Instant now, int maxAttempts, Instant enabledAfter) {
        return lockNotifiable(now, maxAttempts, enabledAfter, Limit.of(1)).stream().findFirst();
    }

    /**
     * 알림 대상 행을 잠그는 실제 쿼리다. 조건 두 가지의 의미를 풀면 이렇다.
     *
     * {@code notifyAttempts < :maxAttempts}: 실패가 상한에 닿아 자동 재시도를 멈춘 행이 다시 잡히는 것을
     * 막는 1차 안전장치다. 먼 미래로 밀어 둔 {@code notify_next_at} 값에 의존하지 않는다.
     *
     * {@code lastActivityAt >= :enabledAfter}: 알림 기능 도입 시각 이전에 끝난 파이프라인을 대상에서 뺀다.
     * 끝난 파이프라인 행은 그 뒤로 갱신되지 않으므로 lastActivityAt이 곧 끝난 시각이다.
     * 덕분에 옛 행에 표시를 남기는 마이그레이션 없이도, 알림을 처음 켜는 순간 과거에 끝난 파이프라인
     * 전부가 한꺼번에 알림으로 쏟아지는 사태를 막는다. 이 조건은 한 번 쓰고 마는 것이 아니라
     * 계속 유지해야 한다 — 빼면 옛 파이프라인 전부가 다시 알림 대상이 된다.
     *
     * 정렬은 {@code notify_next_at} 오름차순(NULL이 먼저 — 갓 끝난 행이 재시도 대기 행보다 앞선다),
     * 같으면 {@code id} 오름차순이라 결과 순서가 항상 같다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select p from Pipeline p "
            + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
            + "com.bff.pipeline.enums.PipelineStatus.FAILED, "
            + "com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
            + "and p.notifiedAt is null "
            + "and p.lastActivityAt >= :enabledAfter "
            + "and p.notifyAttempts < :maxAttempts "
            + "and (p.notifyNextAt is null or p.notifyNextAt <= :now) "
            + "and (p.notifyClaimedUntil is null or p.notifyClaimedUntil < :now) "
            + "order by p.notifyNextAt asc, p.id asc")
    List<Pipeline> lockNotifiable(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
            @Param("enabledAfter") Instant enabledAfter, Limit limit);

    /**
     * 자동 재시도를 중단한 채(전송 실패가 maxAttempts에 도달) 아직 알림이 나가지 못한 파이프라인 수를 센다.
     * 0보다 크면 사람이 개입해야 한다는 신호다. 이 카운트가 give-up 경보의 기준 값이고
     * (로그가 아니라 DB에서 센 이 수가 기준이다), 스케줄러가 주기적으로 읽어 경보 로그를 남긴다.
     */
    @Query("select count(p) from Pipeline p "
            + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
            + "com.bff.pipeline.enums.PipelineStatus.FAILED, "
            + "com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
            + "and p.notifiedAt is null "
            + "and p.notifyAttempts >= :maxAttempts")
    long countGivenUp(@Param("maxAttempts") int maxAttempts);
}
