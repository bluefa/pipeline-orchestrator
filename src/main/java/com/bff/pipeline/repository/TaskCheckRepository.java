package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskCheck;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TaskCheck} 관찰(observation) 행의 영속성 계층이다. 시도당 행은 최대 하나이고 {@code task_attempt_id}로
 * 식별하며, poll 요약을 제자리에서 갱신하려고 조회한다. 엔진 로직은 절대 읽지 않는 쓰기 전용 진단(write-only
 * diagnostics) 데이터다.
 */
public interface TaskCheckRepository extends JpaRepository<TaskCheck, Long> {

    Optional<TaskCheck> findByTaskAttemptId(Long taskAttemptId);
}
