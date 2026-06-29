package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.repository.PipelineRepository;
import java.util.Locale;
import java.util.Optional;
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
 *
 * <p>Only the active-target uniqueness violation means "a run already exists" (recognized by the
 * constraint name, or the exception message as a fallback for drivers that omit it); any other
 * integrity violation is a real bug and surfaces. The insert is retried (bounded) because the active
 * run can terminalize between a failed insert and the recovery lookup, freeing the target — in that
 * window the lookup is empty and the right answer is to retry the insert, not to surface the now-stale
 * violation. That window can only open once per concurrent terminalization, so a couple of tries suffice.
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
        DataIntegrityViolationException lastViolation = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return inserter.insert(target, type);
            } catch (DataIntegrityViolationException duplicate) {
                if (!isActiveTargetViolation(duplicate)) {
                    throw duplicate;
                }
                Optional<Pipeline> active = pipelines.findByActiveTarget(target);
                if (active.isPresent()) {
                    return active.get();
                }
                lastViolation = duplicate;
            }
        }
        throw lastViolation;
    }

    private static boolean isActiveTargetViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null
                    && constraintViolation.getConstraintName().toLowerCase(Locale.ROOT).contains(ACTIVE_TARGET_CONSTRAINT)) {
                return true;
            }
        }
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(ACTIVE_TARGET_CONSTRAINT);
    }
}
