package com.bff.pipeline.entity;

import com.bff.pipeline.config.PipelineSettings;
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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * pipeline 순서 체인(ordered chain)의 한 단계(step)를 나타내는 행이다(ADR-016 §2). task 행 자체가 상태 머신의
 * 상태(state machine state)이며, 진행 원장(progress ledger)을 따로 두지 않는다. {@code (pipelineId, sequence)}는
 * 유일(unique)하고, 엔진은 sequence가 가장 낮은 비종료(non-terminal) task를 현재 task로 고른다.
 *
 * <p>task별 설정 필드({@code timeToLive}, {@code pollingInterval}, {@code executionTimeout},
 * {@code maxFailCount})는 nullable 오버라이드다. null이면 전역 {@code PipelineSettings} 기본값을 쓴다.
 * 이 값들은 {@link JdbcTypeCode}를 거쳐 BIGINT(나노초 단위, Hibernate의 Duration 매핑 방식)로 저장된다.
 *
 * <p>{@code sequence}는 체인 안에서의 위치다. {@code taskName}은 task 타입 이름이며, 엔진은 이를 {@code TaskType}으로
 * 풀어 해당 task를 구동한다. dispatch가 돌려준 원시 response는 더 이상 task에 두지 않는다(ADR-016 ed97ec0):
 * {@code task_attempt.response}에만 남고, 완료 판정은 최신 attempt를 읽는 {@code check(attempt, task)}가 한다
 * (§3 invariant 1). {@code errorCode}는 {@code status == FAILED}일 때만 채운다. {@code nextCheckAt}은 폴링 task가
 * 다음에 실행될 시각이고, null은 곧바로 실행 대상이라는 뜻이다. {@code version}은 낙관적 락(optimistic lock)이다.
 * ADR-021 실행 모델에서는 모든 task 쓰기가 tx2의 pipeline 행 {@code FOR UPDATE} 잠금 아래로 직렬화되므로
 * (cancel도 같은 잠금을 두고 경합한다) 순서 보장은 사실상 pipeline 잠금이 도맡고, {@code @Version}은 방어적
 * 다중화(defense-in-depth)로 남는다 — 잠금 경로 밖에서 뜻밖의 동시 쓰기가 끼어들어도 stale 저장을 거부한다.
 */
@Entity
@Table(
        name = "task",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_pipeline_sequence", columnNames = {"pipeline_id", "sequence"}),
        indexes = @Index(name = "idx_task_name_status", columnList = "task_name, status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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
