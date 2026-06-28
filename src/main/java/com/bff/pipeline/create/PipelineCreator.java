package com.bff.pipeline.create;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.repository.PipelineRepository;
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

    public Pipeline create(String target, PipelineType type) {
        try {
            return inserter.insert(target, type);
        } catch (DataIntegrityViolationException duplicate) {
            return pipelines.findFirstByTargetAndStatus(target, PipelineStatus.RUNNING)
                    .orElseThrow(() -> duplicate);
        }
    }
}
