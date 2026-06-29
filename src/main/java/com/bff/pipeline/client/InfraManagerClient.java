package com.bff.pipeline.client;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;

/**
 * The InfraManager boundary — the one real interface in this module (production is HTTP-backed;
 * tests substitute a fake). Every method is a short, synchronous call that returns a handle or a
 * status, never the job's eventual result.
 *
 * <p><b>Failures are exceptions here.</b> A call that errors or is rejected throws; the production
 * adapter (driven by the ADR-021 runner, which owns the per-call timeout) raises
 * {@link CallTimeoutException} on a timeout and {@link CallInterruptedException} on an interrupt. The
 * domain ({@code TaskMachine}) is the single boundary that catches these and translates them into a
 * persisted {@code ErrorCode} — CallTimeout → CALL_TIMEOUT, other failures → CHECK_ERROR — while
 * CallInterrupted is rethrown as a fail-fast signal. See {@code docs/exception-strategy.md}.
 *
 * <p>Every dispatch is <b>idempotent</b> (ADR-016 §5): a duplicate submit leaves the infrastructure
 * correct, which is what makes at-least-once re-dispatch safe.
 */
public interface InfraManagerClient {

    /** Dispatch a Terraform job for the operation; returns the job id. */
    String runTerraform(String target, TaskOperation operation);

    /** Read a Terraform job's status by its handle. */
    TerraformPoll terraformJobStatus(String jobId);

    /** Probe whether the operation's condition is met yet. */
    boolean checkCondition(String target, TaskOperation operation);

    /** A single InfraManager call exceeded the per-call timeout (→ {@code ErrorCode.CALL_TIMEOUT}). */
    final class CallTimeoutException extends RuntimeException {
        public CallTimeoutException() {
            super("InfraManager call exceeded the per-call timeout");
        }
    }

    /** The calling thread was interrupted (e.g. shutdown) — a fail-fast runtime signal, not a business outcome. */
    final class CallInterruptedException extends RuntimeException {
        public CallInterruptedException() {
            super("InfraManager call interrupted");
        }
    }
}
