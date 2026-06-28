package com.bff.pipeline.reconcile;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the reconciler tick on a fixed cadence (single node, so the tick simply runs — no leader
 * election). Tests drive {@link Reconciler#tick()} directly; the initial delay is configurable so a
 * full-context test can push the background tick out of the way.
 */
@Component
public class ReconcileScheduler {

    private final Reconciler reconciler;

    public ReconcileScheduler(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            fixedDelayString = "${pipeline.tick-interval}",
            initialDelayString = "${pipeline.scheduler-initial-delay:PT5S}")
    public void tick() {
        reconciler.tick();
    }
}
