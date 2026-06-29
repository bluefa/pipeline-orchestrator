package com.bff.pipeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The poll-phase summary of one attempt (ADR-016 §3) — at most one row per attempt (1:0..1),
 * <b>updated in place</b>, so the row count grows with attempts, not polls. Write-only observation:
 * the engine never reads it.
 *
 * <p>{@code callCount} counts the in-progress / errored poll observations of the attempt (the
 * terminal outcome itself lives on {@link TaskAttempt}). The sub-counters answer the first-cause
 * questions: was a TTL-expired condition NOT_MET vs API-failed, and how many polls churned.
 * {@code lastExternalStatus} is a free-form debug label of the last poll (e.g. "RUNNING", "NOT_MET"),
 * never read for logic. {@code lastResponseCode}/{@code lastResponseSummary} are populated by a future
 * HTTP adapter.
 */
@Entity
@Table(
        name = "task_check",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_check_attempt", columnNames = "task_attempt_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_attempt_id", nullable = false)
    private Long taskAttemptId;

    @Column(nullable = false)
    private int callCount;
    @Column(nullable = false)
    private int notMetCount;
    @Column(nullable = false)
    private int apiErrorCount;
    @Column(nullable = false)
    private int callTimeoutCount;

    private String lastExternalStatus;
    private Integer lastResponseCode;
    private String lastResponseSummary;
    private Instant lastCheckedAt;
}
