package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskCheck;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link TaskCheck} observation rows — at most one per attempt
 * ({@code task_attempt_id}), looked up so the poll summary can be updated in place. Write-only
 * diagnostics the engine never reads for logic.
 */
public interface TaskCheckRepository extends JpaRepository<TaskCheck, Long> {

    Optional<TaskCheck> findByTaskAttemptId(Long taskAttemptId);
}
