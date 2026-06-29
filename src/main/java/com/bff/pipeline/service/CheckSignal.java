package com.bff.pipeline.service;

/**
 * One poll observation worth counting (ADR-016 §3) — debuggability only; the terminal DONE/FAILED
 * outcome lives on the attempt, not here. A {@link TaskType#check} reports {@link #RUNNING}/{@link #NOT_MET}
 * for a still-pending poll; the engine reports {@link #API_ERROR}/{@link #CALL_TIMEOUT} for a failed call.
 *
 * <p>{@code RUNNING} — a terraform job is still running; {@code NOT_MET} — a condition is not yet met;
 * {@code API_ERROR} — a poll call returned an error; {@code CALL_TIMEOUT} — a poll call exceeded the
 * per-call timeout.
 */
public enum CheckSignal {
    RUNNING,
    NOT_MET,
    API_ERROR,
    CALL_TIMEOUT
}
