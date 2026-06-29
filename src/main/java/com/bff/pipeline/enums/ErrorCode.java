package com.bff.pipeline.enums;

/**
 * Why a task failed — the canonical, persisted failure reason (ADR-016 §6). Each value is a
 * distinct cause, not a compressed bucket. This is the "message" form of a business failure:
 * a failed task carries an {@code ErrorCode} on its row rather than throwing (see
 * {@code docs/exception-strategy.md}).
 *
 * <p>The causes: {@code JOB_FAILED} — a TERRAFORM_JOB poll reported the job FAILED;
 * {@code EXECUTION_TIMEOUT} — a TERRAFORM_JOB ran past its per-task execution timeout;
 * {@code TIME_TO_LIVE_EXPIRED} — a CONDITION_CHECK was never met within its TTL;
 * {@code CHECK_ERROR} — a dispatch/poll call returned an error (a read failure, not a job failure);
 * {@code CALL_TIMEOUT} — a single InfraManager call exceeded the per-call timeout;
 * {@code UNKNOWN_TASK} — the task's stored name has no registered {@code TaskType}, so it is no longer
 * a defined task.
 */
public enum ErrorCode {
    JOB_FAILED,
    EXECUTION_TIMEOUT,
    TIME_TO_LIVE_EXPIRED,
    CHECK_ERROR,
    CALL_TIMEOUT,
    UNKNOWN_TASK
}
