package com.bff.pipeline.domain;

/**
 * Task lifecycle (ADR-016 §2).
 *
 * <pre>
 *   BLOCKED ──▶ READY ──▶ IN_PROGRESS ──▶ DONE | FAILED | CANCELLED
 * </pre>
 *
 * A task is created BLOCKED (except the first task of a pipeline, created READY) and flips to
 * READY once its predecessor reaches DONE. The <em>current task</em> is the lowest-seq
 * READY/IN_PROGRESS task; tasks ahead of it stay BLOCKED.
 */
public enum TaskStatus {
    BLOCKED,
    READY,
    IN_PROGRESS,
    DONE,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
