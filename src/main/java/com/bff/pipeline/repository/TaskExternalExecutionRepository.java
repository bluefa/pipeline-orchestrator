package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskExternalExecution;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task별 요청 정체성을 유일 키로 조회한다. 모든 변경은 소유권을 검증한 생성 또는 상태 기록 트랜잭션에 참여한다. */
public interface TaskExternalExecutionRepository extends JpaRepository<TaskExternalExecution, Long> {
    Optional<TaskExternalExecution> findByTaskId(Long taskId);
}
