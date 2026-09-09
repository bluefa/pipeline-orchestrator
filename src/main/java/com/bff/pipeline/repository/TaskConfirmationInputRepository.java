package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskConfirmationInput;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task별 고정 입력을 유일 인덱스로 조회한다. 쓰기는 점유 소유권을 확인한 상태 기록 트랜잭션에 참여한다. */
public interface TaskConfirmationInputRepository extends JpaRepository<TaskConfirmationInput, Long> {
    Optional<TaskConfirmationInput> findByTaskId(Long taskId);
}
