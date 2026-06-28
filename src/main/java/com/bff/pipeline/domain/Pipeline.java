package com.bff.pipeline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One run for one target (ADR-016 §2, §4). The row is the state.
 *
 * <p><b>One active pipeline per target</b> is enforced by a single unique constraint on
 * {@code active_target}. MySQL has no partial (filtered) unique index, so instead of a
 * {@code WHERE status non-terminal} index this column carries the invariant directly:
 * {@code active_target = target} while the pipeline is non-terminal, and {@code NULL} once it
 * terminates. MySQL admits multiple NULLs in a unique index, so terminal rows never collide,
 * while at most one non-terminal row can hold a given target. The column is maintained by the
 * application in the same transaction as the status change (create sets it; cancel and converge
 * clear it).
 */
@Entity
@Table(
        name = "pipeline",
        uniqueConstraints = @UniqueConstraint(name = "uq_pipeline_active_target", columnNames = "active_target"),
        indexes = @Index(name = "ix_pipeline_status", columnList = "status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineType type;

    @Column(nullable = false)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActivityAt;

    /** Invariant: equals {@code target} while non-terminal, {@code NULL} once terminal. */
    @Column(name = "active_target")
    private String activeTarget;
}
