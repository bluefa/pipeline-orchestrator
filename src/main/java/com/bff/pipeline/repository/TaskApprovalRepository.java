package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.enums.ApprovalStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link TaskApproval} 행의 영속성 계층이다. 결정을 바꾸는 질의는 전부 "지금 REQUESTED일 때만"이라는
 * 조건을 달고 나가며, 갱신된 행 수가 곧 판정 결과다 — 1이면 이번 호출이 이겼고, 0이면 다른 누군가가 먼저
 * 끝냈거나 시간 조건에 걸린 것이다(승인 게이트 ADR §결정 4).
 *
 * {@link #expireIfDue}가 시간 조건을 다는 것은 승인을 기록하는 질의와 정확한 여집합을 이루기 위해서다 —
 * 만료 처리는 만료 시각을 지난 뒤에만, 승인은 그 전에만 통과한다. 파드마다 시계가 조금씩 다를 수 있는
 * 환경에서 두 질의가 같은 행을 동시에 노려도 판정이 뒤집히지 않는 근거이며, 조건이 없으면 시계가 몇 초
 * 빠른 파드가 아직 유효한 승인 창을 닫아 버릴 수 있다. 짝이 되는 승인 질의는 결정 API와 함께 들어온다.
 *
 * 이 질의들을 부르기 전에 호출자는 반드시 파이프라인 행을 먼저 잠근다(승인 게이트 ADR 불변식 6).
 * 순서가 갈리면 만료 시각 부근에서 워커와 승인 API가 서로의 잠금을 기다리는 교착에 빠진다.
 *
 * 이 질의들은 영속 컨텍스트를 비우지 않는다. 비우면 같은 트랜잭션이 들고 있던 파이프라인·태스크
 * 객체가 통째로 분리돼, 그 뒤에 바꾼 상태가 조용히 저장되지 않는다 — 워커의 마무리 트랜잭션이 만료를
 * 기록한 직후 파이프라인을 실패로 닫는 경로가 정확히 그 모양이라 실제로 유실됐다. 대신 호출자는 두 가지를
 * 지킨다: 이 질의를 부르기 전에 같은 승인 행을 엔티티로 읽어 두지 않고(읽어 두면 결과를 다시 읽을 때 낡은
 * 값이 나온다), 승인 행의 상태는 setter가 아니라 오직 이 질의들로만 바꾼다.
 */
public interface TaskApprovalRepository extends JpaRepository<TaskApproval, Long> {

    Optional<TaskApproval> findByTaskId(Long taskId);

    /**
     * 만료를 확정한다 — 워커가 파이프라인 행을 잠근 채 부르며, 1행이면 이번 사이클이 만료를 확정한 것이라
     * 게이트 태스크를 실패로 닫는다. 0행이면 이미 결정됐거나 아직 만료 시각이 되지 않은 것이다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TaskApproval a set a.status = com.bff.pipeline.enums.ApprovalStatus.EXPIRED, a.decidedAt = :now "
            + "where a.taskId = :taskId and a.status = com.bff.pipeline.enums.ApprovalStatus.REQUESTED "
            + "and a.expiresAt <= :now")
    int expireIfDue(@Param("taskId") Long taskId, @Param("now") Instant now);

    /**
     * 파이프라인이 취소돼 요청 자체가 무의미해진 경우 요청을 닫는다. 시간 조건이 없는 유일한 결정 전이다 —
     * 취소는 만료 시각과 무관하게 언제나 유효하기 때문이다.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TaskApproval a set a.status = com.bff.pipeline.enums.ApprovalStatus.CANCELLED, a.decidedAt = :now "
            + "where a.taskId = :taskId and a.status = com.bff.pipeline.enums.ApprovalStatus.REQUESTED")
    int cancelIfRequested(@Param("taskId") Long taskId, @Param("now") Instant now);
}
