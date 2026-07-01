package com.bff.pipeline.entity;

import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 하나의 target에 대한 한 번의 실행(run)을 나타내는 행이다(ADR-016 §2, §4). 이 행 자체가 곧 상태(state)다.
 *
 * <p><b>target당 활성 pipeline은 하나</b>라는 불변식은 {@code active_target} 컬럼의 유일 제약(unique constraint)
 * 하나로 강제한다. MySQL은 부분(filtered) 유일 인덱스를 지원하지 않으므로 {@code WHERE status non-terminal} 같은
 * 인덱스 대신 이 컬럼이 불변식을 직접 짊어진다. pipeline이 비종료(non-terminal) 상태인 동안에는
 * {@code active_target = target}이고, 종료되면 {@code NULL}로 비운다. MySQL은 유일 인덱스에 여러 NULL을 허용하니
 * 종료된 행끼리는 서로 부딪히지 않고, 특정 target의 비종료 행은 언제나 최대 하나뿐이다. 이 컬럼은 상태 변경과 같은
 * 트랜잭션 안에서 애플리케이션이 직접 관리한다 — 생성 시 채우고 cancel·converge 시 비운다.
 */
@Entity
@Table(
        name = "pipeline",
        uniqueConstraints = @UniqueConstraint(name = Pipeline.ACTIVE_TARGET_CONSTRAINT, columnNames = "active_target"),
        indexes = {
                @Index(name = "idx_pipeline_claim", columnList = "status, next_due_at"),
                @Index(name = "idx_pipeline_claimed_until", columnList = "claimed_until")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Pipeline {

    public static final String ACTIVE_TARGET_CONSTRAINT = "uq_pipeline_active_target";

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

    @Column(name = "active_target")
    private String activeTarget;

    // ── ADR-021 실행 좌표(execution-coordination) 컬럼: claim/lease/cooperative-cancel. 도메인 상태와는 분리된다. ──

    /**
     * 다음 claim 대상 시각. 과거(&lt;= now)면 due다. 생성 시 {@code now}로 시딩하고 성공 report/reschedule가 앞으로
     * 당기므로 항상 non-null이다(claim predicate {@code next_due_at <= now}는 null을 due로 보지 않아, null이면 영영 unclaimed).
     */
    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** claim할 때마다 새로 발급하는 fencing token(UUID). write-back 트랜잭션의 guarded write-back 소유권 키다. */
    @Column(name = "claimed_by")
    private String claimedBy;

    /** lease 만료 시각. {@code < now}면 claim이 저절로 풀려 다음 스캔이 reclaim한다(ADR-021 Decision 5). */
    @Column(name = "claimed_until")
    private Instant claimedUntil;

    /** cooperative cancel(Case B) 플래그. claim을 쥔 워커가 안전지점에서 읽어 스스로 CANCELLED를 적용한다. */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;
}
