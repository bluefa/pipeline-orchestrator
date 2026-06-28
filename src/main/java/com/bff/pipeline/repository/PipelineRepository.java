package com.bff.pipeline.repository;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    /** The reconciler's due-scan: every running pipeline, oldest first. */
    List<Pipeline> findByStatusOrderByIdAsc(PipelineStatus status);

    /**
     * The current active run for a target, if any. Uniqueness is per target (one active pipeline
     * per target), so this does not filter by type — used to recover the existing run after a
     * duplicate-create unique violation.
     */
    Optional<Pipeline> findFirstByTargetAndStatus(String target, PipelineStatus status);

    /**
     * Converge a pipeline to a terminal status, clearing {@code active_target} so the target is
     * reusable. Guarded: only a RUNNING pipeline is moved, so a converge can never clobber a
     * pipeline a concurrent cancel already moved to CANCELLED. A 0-row result is that no-op.
     * Flush+clear so the caller re-reads committed state, not a stale first-level-cache entity.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.status = :to, p.activeTarget = null, p.lastActivityAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.domain.PipelineStatus.RUNNING")
    int finish(@Param("id") Long id, @Param("to") PipelineStatus to, @Param("now") Instant now);
}
