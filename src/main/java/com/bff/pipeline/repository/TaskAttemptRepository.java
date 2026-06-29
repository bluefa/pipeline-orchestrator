package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TaskAttempt} 관찰(observation) 행(row)의 영속성 계층이다. 시도당 하나의 행이
 * {@code (taskId, attemptNumber)} 키로 식별된다. 현재 시도는 해당 키로 조회되며, 모든 시도는
 * {@code attemptNumber} 오름차순으로 나열된다. 이 테이블은 엔진이 로직에 절대 읽지 않는
 * 쓰기 전용 진단(write-only diagnostics) 데이터이다.
 */
public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    Optional<TaskAttempt> findByTaskIdAndAttemptNumber(Long taskId, int attemptNumber);

    List<TaskAttempt> findByTaskIdOrderByAttemptNumberAsc(Long taskId);
}
