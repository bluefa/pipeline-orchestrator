package com.bff.pipeline.service;

import com.bff.pipeline.entity.Task;

/**
 * A kind of task and how it runs. The engine stores each task's {@link #taskName()} on its row and
 * resolves the matching {@code TaskType} (via {@link TaskTypeRegistry}) to drive it — so a new kind of
 * task is a new implementation that registers itself, not an edit to a {@code switch} (ADR-016 §2
 * extension seam). This is the one genuine polymorphism boundary in the engine, which is why it earns
 * an interface (each implementation is a real, distinct behaviour, not a single-implementation indirection).
 *
 * <p>A {@code TaskType} only knows how to <em>start</em> and <em>poll</em> one task; the engine owns
 * the surrounding state machine, retry/fail decision, observation writes, and the translation of an
 * {@code InfraManagerClient} call exception into a persisted {@code ErrorCode}.
 *
 * <p>{@code taskName()} is the stable name persisted on every task of this type, by which the registry
 * resolves a task row. {@code attempt(target, task)} starts the external work idempotently (ADR-016 §5)
 * — e.g. submit a job and record its handle on the task — and may be a no-op for a type whose work is
 * purely polling; it signals a <em>failed call</em> only by throwing a {@code RuntimeException} (the
 * engine maps {@code CallTimeoutException} to CALL_TIMEOUT, rethrows {@code CallInterruptedException} as
 * fail-fast, and any other — including a guard on a null/blank job id — to CHECK_ERROR), never a
 * <em>business</em> failure. {@code check(target, task)} polls progress once and reports what the engine
 * should do next as a {@link TaskProgress} value — complete it, leave it pending, or fail it (with a
 * retryable flag) — a business failure being data here, never an exception (see
 * {@code docs/exception-strategy.md}).
 */
public interface TaskType {

    String taskName();

    void attempt(String target, Task task);

    TaskProgress check(String target, Task task);
}
