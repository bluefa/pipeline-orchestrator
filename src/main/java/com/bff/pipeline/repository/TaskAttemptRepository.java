package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TaskAttempt} 관찰(observation) 행의 영속성 계층이다. 시도 하나가 곧 행 하나이며 {@code (taskId, attemptNumber)}
 * 키로 식별한다. 현재 시도는 이 키로 조회하고, 전체 시도는 {@code attemptNumber} 오름차순으로 나열한다.
 * 엔진은 완료를 판정할 때 최신 {@code (taskId, attemptNumber)} 행을 읽는다(ADR-016 §3 invariant 1: 완료 판정은 최신
 * attempt의 {@code response}를 코드 레벨에서 check). claim·스케줄링·전이는 이 테이블을 건드리지 않는다.
 */
public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    Optional<TaskAttempt> findByTaskIdAndAttemptNumber(Long taskId, int attemptNumber);

    List<TaskAttempt> findByTaskIdOrderByAttemptNumberAsc(Long taskId);
}
