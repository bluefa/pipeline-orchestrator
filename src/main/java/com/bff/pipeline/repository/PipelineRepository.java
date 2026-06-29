package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link Pipeline} rows. {@code findByActiveTarget} returns the current active run for a
 * target if any: by invariant {@code active_target == target} exactly while a run is non-terminal
 * (cleared to NULL on terminalization), so it lands on the {@code uq_pipeline_active_target} unique
 * index and returns at most one row — used to recover the existing run after a duplicate-create unique
 * violation. {@code finish} converges a pipeline to a terminal status and clears {@code active_target}
 * so the target is reusable; it is guarded to move only a RUNNING pipeline, so a converge can never
 * clobber a pipeline a concurrent cancel already moved to CANCELLED (a 0-row result is that no-op), and
 * it flushes+clears so the caller re-reads committed state, not a stale first-level-cache entity.
 */
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    Optional<Pipeline> findByActiveTarget(String activeTarget);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.status = :to, p.activeTarget = null, p.lastActivityAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING")
    int finish(@Param("id") Long id, @Param("to") PipelineStatus to, @Param("now") Instant now);
}
