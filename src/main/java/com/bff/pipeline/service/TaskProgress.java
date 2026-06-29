package com.bff.pipeline.service;

import com.bff.pipeline.enums.ErrorCode;

/**
 * The outcome of one {@link TaskType#check} poll, as a value the engine acts on. A business failure is
 * data here — a persisted {@code ErrorCode} — never a thrown exception (see
 * {@code docs/exception-strategy.md}). The engine switches over the three cases exhaustively.
 */
public sealed interface TaskProgress {

    /** The task's work finished successfully → the engine completes the task. */
    record Succeeded() implements TaskProgress {}

    /** Still working — not done and not failed. {@code observed} is recorded for diagnosis only. */
    record Pending(CheckSignal observed) implements TaskProgress {}

    /**
     * The task failed. {@code reason} is the persisted cause; {@code retryable} says whether a fresh
     * re-run is allowed before the fail-count cap (a job failure retries; an expired TTL does not).
     */
    record Failed(ErrorCode reason, boolean retryable) implements TaskProgress {}

    TaskProgress SUCCEEDED = new Succeeded();

    static TaskProgress pending(CheckSignal observed) {
        return new Pending(observed);
    }

    static TaskProgress failed(ErrorCode reason, boolean retryable) {
        return new Failed(reason, retryable);
    }
}
