package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
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
 * state — there is no separate progress ledger. {@code (pipelineId, sequence)} is unique; the engine
 * picks the lowest-sequence non-terminal task as the current one.
 *
 * <p>The per-task knob fields ({@code timeToLive}, {@code pollingInterval}, {@code executionTimeout},
 * {@code maxFailCount}) are nullable overrides; when null the global {@code PipelineSettings}
 * default applies. They are stored as BIGINT (millis) via {@link JdbcTypeCode}.
 *
 * <p>{@code sequence} is the position in the chain. {@code taskName} is the task type's name, which the
 * engine resolves to a {@code TaskType} to drive this task. {@code jobId} is the InfraManager-issued
 * handle for a TERRAFORM_JOB, stored on dispatch and re-polled after a crash. {@code errorCode} is set
 * only when {@code status == FAILED}. {@code nextCheckAt} is when a polling task is next due, with null
 * meaning due now. {@code version} is an optimistic lock: a cancel that commits CANCELLED during a slow
 * InfraManager call bumps it, so the in-flight advance's stale save is rejected rather than clobbering
 * the terminal state.
 */
@Entity
@Table(
        name = "task",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_pipeline_sequence", columnNames = {"pipeline_id", "sequence"}))
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

    @Column(nullable = false)
    private int sequence;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    private String jobId;

    @Column(nullable = false)
    private int failCount;

    @Enumerated(EnumType.STRING)
    private ErrorCode errorCode;

    private Instant startedAt;
    private Instant readyAt;
    private Instant finishedAt;

    private Instant nextCheckAt;

    @JdbcTypeCode(SqlTypes.BIGINT)
    private Duration timeToLive;

    @JdbcTypeCode(SqlTypes.BIGINT)
    private Duration pollingInterval;

    @JdbcTypeCode(SqlTypes.BIGINT)
    private Duration executionTimeout;

    private Integer maxFailCount;

    @Version
    private Long version;
}
