package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TaskAttempt} 관찰(observation) 행(row)의 영속성 계층이다. 시도당 하나의 행이
 * {@code (taskId, attemptNumber)} 키로 식별된다. 현재 시도는 해당 키로 조회되며, 모든 시도는
 * {@code attemptNumber} 오름차순으로 나열된다. 엔진은 완료 판정을 위해 최신 {@code (taskId, attemptNumber)}
 * 행을 읽는다(ADR-016 §3 invariant 1: 완료는 최신 attempt의 {@code response} 위의 코드 레벨 check);
 * 그 외 claim/스케줄링/전이는 이 테이블을 읽지 않는다.
 */
public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    Optional<TaskAttempt> findByTaskIdAndAttemptNumber(Long taskId, int attemptNumber);

    List<TaskAttempt> findByTaskIdOrderByAttemptNumberAsc(Long taskId);
}
