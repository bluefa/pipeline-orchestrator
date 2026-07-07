package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TerraformJobState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TerraformJobState} 관찰 행의 영속성 계층이다. 엔진 로직은 절대 읽지 않는 쓰기 전용 진단 데이터이며,
 * 조회는 관찰 기록의 upsert 가드(재실행 시 같은 (task, attempt, job) 행을 제자리 갱신)와 admin 조회 경로
 * (P5 attempt 인라인·per-job 상태 엔드포인트)만 쓴다 — "엔진은 절대 읽지 않는다"는 invariant는 claim·스케줄링·
 * 전이 얘기지 admin 조회 얘기가 아니다({@code terraform_result}와 같은 선례).
 */
public interface TerraformJobStateRepository extends JpaRepository<TerraformJobState, Long> {

    /** upsert 가드 + per-job 상태 엔드포인트 — 유니크 키 전체를 지정해 행 하나만 읽는다. */
    Optional<TerraformJobState> findByTaskIdAndAttemptNumberAndJobId(Long taskId, int attemptNumber, String jobId);

    /** P5 attempt 인라인 — task의 전 job 상태를 attempt_number로 접어 병합한다. */
    List<TerraformJobState> findByTaskIdOrderByAttemptNumberAscIdAsc(Long taskId);
}
