package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TerraformResult;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TerraformResult} 관찰(observation) 행의 영속성 계층이다. 엔진 로직은 절대 읽지 않는 쓰기 전용 진단
 * 데이터이며, 존재 검사는 재실행(크래시/리스 회수 후 re-poll) 시 이미 기록된 job을 건너뛰는 멱등 가드다.
 */
public interface TerraformResultRepository extends JpaRepository<TerraformResult, Long> {

    boolean existsByTaskIdAndAttemptNumberAndJobId(Long taskId, int attemptNumber, String jobId);
}
