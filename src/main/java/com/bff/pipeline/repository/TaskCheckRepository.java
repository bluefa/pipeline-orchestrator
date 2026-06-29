package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskCheck;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link TaskCheck} 관찰(observation) 행(row)의 영속성 계층이다. 시도당 최대 하나의 행이
 * {@code task_attempt_id}로 식별되며, 폴(poll) 요약 정보를 제자리에서 갱신(update in place)하기 위해
 * 조회된다. 엔진이 로직에 절대 읽지 않는 쓰기 전용 진단(write-only diagnostics) 데이터이다.
 */
public interface TaskCheckRepository extends JpaRepository<TaskCheck, Long> {

    Optional<TaskCheck> findByTaskAttemptId(Long taskAttemptId);
}
