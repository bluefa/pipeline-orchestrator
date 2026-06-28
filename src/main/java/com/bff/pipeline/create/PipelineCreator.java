package com.bff.pipeline.create;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.repository.PipelineRepository;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotent create (ADR-016 §4). Starts a run for the target, or — if one is already active —
 * returns that existing run rather than erroring. This honors the trigger contract: a duplicate
 * create of any type is a safe no-op that yields the in-flight run.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: when the inserter's transaction rolls back on
 * the unique violation, the recovery lookup must run in a fresh transaction to read the committed
 * existing run. The {@link DataIntegrityViolationException} catch is the one external/infrastructure
 * failure we translate into a domain answer; see {@code docs/exception-strategy.md}.
 */
@Service
public class PipelineCreator {

    private final PipelineInserter inserter;
    private final PipelineRepository pipelines;

    public PipelineCreator(PipelineInserter inserter, PipelineRepository pipelines) {
        this.inserter = inserter;
        this.pipelines = pipelines;
    }

    private static final String ACTIVE_TARGET_CONSTRAINT = "uq_pipeline_active_target";

    public Pipeline create(String target, PipelineType type) {
        try {
            return inserter.insert(target, type);
        } catch (DataIntegrityViolationException duplicate) {
            // Only the active-target uniqueness violation means "a run already exists"; any other
            // integrity violation is a real bug and must surface, not be masked as a duplicate.
            if (!isActiveTargetViolation(duplicate)) {
                throw duplicate;
            }
            return pipelines.findFirstByTargetAndStatus(target, PipelineStatus.RUNNING)
                    .orElseThrow(() -> duplicate);
        }
    }

    private static boolean isActiveTargetViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase(Locale.ROOT).contains(ACTIVE_TARGET_CONSTRAINT)) {
                return true;
            }
        }
        // Fallback for drivers that do not populate the constraint name on the exception.
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(ACTIVE_TARGET_CONSTRAINT);
    }
}
