package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per retry attempt of a task (ADR-016 §3) — a <b>write-only observation</b> table: the
 * engine never reads it, and losing it costs only debuggability, never correctness.
 * {@code attemptNo = task.failCount + 1} at the moment the attempt begins, so a task that retried
 * twice and then failed leaves three rows (1, 2, 3). {@code status} is the attempt outcome:
 * IN_PROGRESS while running, then DONE or FAILED.
 *
 * <p>{@code dispatchResponseCode}/{@code dispatchResponseSummary} are populated by a future HTTP
 * InfraManager adapter; they are null until then.
 */
@Entity
@Table(
        name = "task_attempt",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_attempt", columnNames = {"task_id", "attempt_no"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    private ErrorCode errorCode;

    private Integer dispatchResponseCode;
    private String dispatchResponseSummary;

    @Column(nullable = false)
    private Instant startedAt;
    private Instant finishedAt;
}
