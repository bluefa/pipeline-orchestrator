package com.bff.pipeline.repository;

import com.bff.pipeline.domain.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** A pipeline's tasks in chain order; the reconciler picks the lowest-seq non-terminal one. */
    List<Task> findByPipelineIdOrderBySeqAsc(Long pipelineId);
}
