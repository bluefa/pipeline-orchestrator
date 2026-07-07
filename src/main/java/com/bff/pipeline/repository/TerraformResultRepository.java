package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TerraformResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * {@link TerraformResult} 관찰(observation) 행의 영속성 계층이다. 엔진 로직은 절대 읽지 않는 쓰기 전용 진단
 * 데이터이며, 존재 검사는 재실행(크래시/리스 회수 후 re-poll) 시 이미 기록된 job을 건너뛰는 멱등 가드다.
 * admin 조회 경로(P5 메타 인라인·본문 전용 엔드포인트)가 읽는 것은 기존 선례 그대로다 — "엔진은 절대 읽지
 * 않는다"는 invariant는 claim·스케줄링·전이 얘기지 admin 조회 얘기가 아니다(설계 §4.5).
 */
public interface TerraformResultRepository extends JpaRepository<TerraformResult, Long> {

    boolean existsByTaskIdAndAttemptNumberAndJobId(Long taskId, int attemptNumber, String jobId);

    /** 본문 조회는 유니크 키 전체 지정의 행 하나만 — task 상세와 달리 여기서만 MEDIUMTEXT를 지불한다. */
    Optional<TerraformResult> findByTaskIdAndAttemptNumberAndJobId(Long taskId, int attemptNumber, String jobId);

    /** P5 attempt 인라인용 메타 투영 — 본문({@code result})은 존재 여부({@code hasBody})로만 접는다. */
    @Query("""
            select r.attemptNumber as attemptNumber, r.jobId as jobId, r.succeeded as succeeded,
                   r.truncated as truncated,
                   case when r.result is null then false else true end as hasBody,
                   r.createdAt as createdAt
            from TerraformResult r
            where r.taskId = :taskId
            order by r.attemptNumber asc, r.id asc
            """)
    List<TerraformResultMetadata> findMetadataByTaskId(Long taskId);
}
