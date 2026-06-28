package com.bff.pipeline.im;

import com.bff.pipeline.domain.TaskOperation;

/**
 * The InfraManager boundary — the one real interface in this module (production is HTTP-backed;
 * tests substitute a fake). Every method is a short, synchronous call that returns a handle or a
 * status, never the job's eventual result.
 *
 * <p><b>Failures are exceptions here.</b> A call that times out, errors, or is rejected throws; the
 * reconciler ({@code TaskMachine}) is the single boundary that catches these and translates them
 * into a persisted {@code ErrorCode}. See {@code docs/exception-strategy.md}.
 *
 * <p>Every dispatch is <b>idempotent</b> (ADR-016 §5): a duplicate submit leaves the infrastructure
 * correct, which is what makes at-least-once re-dispatch safe.
 */
public interface ImClient {

    /** Dispatch a Terraform job for the operation; returns the job id. */
    String runTerraform(String target, TaskOperation operation);

    /** Read a Terraform job's status by its handle. */
    TerraformPoll terraformJobStatus(String jobId);

    /** Probe whether the operation's condition is met yet. */
    boolean checkCondition(String target, TaskOperation operation);
}
