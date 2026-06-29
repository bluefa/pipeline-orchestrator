package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TaskAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link TaskAttempt} observation rows — one per attempt, keyed by
 * {@code (taskId, attemptNo)}. The current attempt is looked up by that key, and all attempts are
 * listed in {@code attemptNo} order for assertions; these are write-only diagnostics the engine never
 * reads for logic.
 */
public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    Optional<TaskAttempt> findByTaskIdAndAttemptNo(Long taskId, int attemptNo);

    List<TaskAttempt> findByTaskIdOrderByAttemptNoAsc(Long taskId);
}
