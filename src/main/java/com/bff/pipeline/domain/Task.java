package com.bff.pipeline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One step in a pipeline's ordered chain (ADR-016 §2). The task row <em>is</em> the state machine
 * state — there is no separate progress ledger. {@code (pipelineId, seq)} is unique; the reconciler
 * picks the lowest-seq non-terminal task as the current one.
 *
 * <p>The per-task knob fields ({@code ttl}, {@code pollingInterval}, {@code executionTimeout},
 * {@code maxFailCount}) are nullable overrides; when null the global {@code PipelineSettings}
 * default applies. They are stored as BIGINT (millis) via {@link JdbcTypeCode}.
 */
@Entity
@Table(
        name = "task",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_pipeline_seq", columnNames = {"pipeline_id", "seq"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    /** Position in the chain; the lowest-seq non-terminal task is the current one. */
    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    /** InfraManager-issued handle for a TERRAFORM_JOB; stored on dispatch, re-polled after a crash. */
    private String jobId;

    @Column(nullable = false)
    private int failCount;

    /** Set only when {@code status == FAILED}. */
    @Enumerated(EnumType.STRING)
    private ErrorCode errorCode;

    private Instant startedAt;
    private Instant readyAt;
    private Instant finishedAt;

    /** When a polling task is next due; null means due now. */
    private Instant nextCheckAt;

    // ---- per-task overrides (null → global PipelineSettings default) ----
    @JdbcTypeCode(SqlTypes.BIGINT)
    private Duration ttl;

    @JdbcTypeCode(SqlTypes.BIGINT)
    private Duration pollingInterval;

    @JdbcTypeCode(SqlTypes.BIGINT)
    private Duration executionTimeout;

    private Integer maxFailCount;

    /**
     * Optimistic lock. A cancel that commits CANCELLED during a slow InfraManager call bumps this,
     * so the in-flight reconcile's stale save is rejected rather than clobbering the terminal state.
     */
    @Version
    private Long version;
}
