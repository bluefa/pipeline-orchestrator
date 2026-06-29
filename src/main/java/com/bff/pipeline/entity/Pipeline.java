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
 * 하나의 target에 대한 하나의 실행(run)을 나타내는 행(row)이다(ADR-016 §2, §4). 이 행 자체가 상태(state)이다.
 *
 * <p><b>target당 하나의 활성 pipeline</b> 불변식은 {@code active_target} 컬럼의 단일 유일 제약(unique constraint)으로
 * 강제된다. MySQL은 부분(filtered) 유일 인덱스를 지원하지 않으므로, {@code WHERE status non-terminal} 형식의 인덱스 대신
 * 이 컬럼이 불변식을 직접 담는다: pipeline이 비종료(non-terminal) 상태인 동안에는 {@code active_target = target}이며,
 * 종료(terminates) 시에는 {@code NULL}로 설정된다. MySQL은 유일 인덱스에서 다수의 NULL을 허용하므로 종료된 행들은
 * 서로 충돌하지 않으며, 주어진 target에 대해 비종료 행은 최대 하나만 존재할 수 있다. 이 컬럼은 상태 변경과 동일한
 * 트랜잭션 내에서 애플리케이션이 유지 관리한다(생성 시 설정, cancel 및 converge 시 초기화).
 *
 * <p><b>실행 조정 컬럼 (ADR-021, 도메인 상태 아님):</b> {@code next_due_at}, {@code claimed_by},
 * {@code claimed_until}, {@code cancel_requested}는 ADR-021 실행 모델이 소유하는 메타데이터이다.
 * {@code next_due_at}은 다음 워커 픽업 스케줄 시각, {@code claimed_by}/{@code claimed_until}은
 * 파이프라인별 클레임/리스 펜싱 토큰이며, {@code cancel_requested}는 협력적 취소 신호이다.
 * 이 컬럼들은 ADR-016 도메인 상태({@code status}, {@code active_target} 등)와 별개로 관리된다.
 * {@code idx_pipeline_claim} 인덱스({@code status, next_due_at})는 클레임 조건자
 * {@code status='RUNNING' AND next_due_at <= now}를 커버한다(MySQL은 부분 인덱스 미지원).
 * {@code idx_pipeline_claimed_until} 인덱스({@code claimed_until})는 어드미션 카운트 쿼리
 * ({@code PipelineClaimer.countByClaimedUntilAfter})를 지원한다(ADR-021 Decision 7).
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

    @Column(name = "next_due_at")
    private Instant nextDueAt;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;
}
