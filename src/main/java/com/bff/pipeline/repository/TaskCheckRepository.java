package com.bff.pipeline.repository;

import com.bff.pipeline.domain.TaskCheck;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCheckRepository extends JpaRepository<TaskCheck, Long> {

    Optional<TaskCheck> findByTaskAttemptId(Long taskAttemptId);
}
