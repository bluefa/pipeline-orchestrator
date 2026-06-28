package com.bff.pipeline.domain;

/**
 * Why a task failed — the canonical, persisted failure reason (ADR-016 §6). Each value is a
 * distinct cause, not a compressed bucket. This is the "message" form of a business failure:
 * a failed task carries an {@code ErrorCode} on its row rather than throwing (see
 * {@code docs/exception-strategy.md}).
 */
public enum ErrorCode {
    /** A TERRAFORM_JOB poll reported the job FAILED. */
    JOB_FAILED,
    /** A TERRAFORM_JOB ran past its per-task execution timeout. */
    EXECUTION_TIMEOUT,
    /** A CONDITION_CHECK was never met within its TTL. */
    TTL_EXPIRED,
    /** A dispatch/poll call returned an error (a read failure, not a job failure). */
    CHECK_ERROR,
    /** A single InfraManager call exceeded the per-call timeout. */
    CALL_TIMEOUT
}
