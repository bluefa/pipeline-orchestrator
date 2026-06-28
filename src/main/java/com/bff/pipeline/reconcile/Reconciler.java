package com.bff.pipeline.reconcile;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One tick: scan the running pipelines and reconcile each in its own transaction. Each pipeline is
 * isolated in a try/catch so one pipeline's transient failure (an InfraManager error, or an
 * optimistic-lock clash with a concurrent cancel) is logged and skipped — it never aborts the tick
 * and starves the others. Single node, so no leader election.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final PipelineRepository pipelines;
    private final PipelineReconciliation reconciliation;

    public Reconciler(PipelineRepository pipelines, PipelineReconciliation reconciliation) {
        this.pipelines = pipelines;
        this.reconciliation = reconciliation;
    }

    public void tick() {
        for (Pipeline scanned : pipelines.findByStatusOrderByIdAsc(PipelineStatus.RUNNING)) {
            try {
                reconciliation.reconcile(scanned.getId());
            } catch (RuntimeException e) {
                log.warn("reconcile failed for pipeline {} — skipping this tick", scanned.getId(), e);
            }
        }
    }
}
