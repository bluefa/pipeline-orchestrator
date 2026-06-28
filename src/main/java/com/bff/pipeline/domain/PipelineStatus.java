package com.bff.pipeline.domain;

/** Pipeline lifecycle. RUNNING is the only non-terminal state; the reconciler services only it. */
public enum PipelineStatus {
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
