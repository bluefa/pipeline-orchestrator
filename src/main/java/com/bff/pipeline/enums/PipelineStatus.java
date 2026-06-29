package com.bff.pipeline.enums;

/** Pipeline lifecycle. RUNNING is the only non-terminal state; the engine advances only it. */
public enum PipelineStatus {
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
