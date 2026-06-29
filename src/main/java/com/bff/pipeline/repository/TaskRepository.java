package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Task} rows. {@code findByPipelineIdOrderBySequenceAsc} returns a pipeline's
 * tasks in chain order; the engine picks the lowest-sequence non-terminal one as the current task.
 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByPipelineIdOrderBySequenceAsc(Long pipelineId);
}
