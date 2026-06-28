package com.bff.pipeline.domain;

/** What a task does. The kind selects how the task is dispatched and polled. */
public enum TaskKind {
    /** Dispatch a Terraform job, then poll its status until it finishes. */
    TERRAFORM_JOB,
    /** Poll a probe until a condition is met. */
    CONDITION_CHECK
}
