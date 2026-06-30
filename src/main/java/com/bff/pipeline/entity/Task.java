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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * pipeline 순서 체인(ordered chain)의 한 단계(step)를 나타내는 행(row)이다(ADR-016 §2). task 행 자체가
 * 상태 머신의 상태(state machine state)이며, 별도의 진행 원장(progress ledger)은 존재하지 않는다.
 * {@code (pipelineId, sequence)} 조합은 유일(unique)하며, 엔진은 sequence 값이 가장 낮은 비종료(non-terminal) task를
 * 현재 task로 선택한다.
 *
 * <p>task별 설정 필드({@code timeToLive}, {@code pollingInterval}, {@code executionTimeout},
 * {@code maxFailCount})는 nullable 오버라이드 값이다. null인 경우 전역 {@code PipelineSettings} 기본값이 적용된다.
 * 이 값들은 {@link JdbcTypeCode}를 통해 BIGINT(나노초 단위, Hibernate의 Duration 매핑 방식)로 저장된다.
 *
 * <p>{@code sequence}는 체인 내 위치를 나타낸다. {@code taskName}은 task 타입의 이름으로, 엔진이 이를
 * {@code TaskType}으로 해석하여 해당 task를 구동한다. dispatch가 반환한 원시 response는 더 이상 task에 두지
 * 않는다(ADR-016 ed97ec0): {@code task_attempt.response}에만 존재하며, 완료는 최신 attempt를 읽는
 * {@code check(attempt, task)}로 판정한다(§3 invariant 1).
 * {@code errorCode}는 {@code status == FAILED}인 경우에만 설정된다. {@code nextCheckAt}은 폴링 task가 다음에
 * 실행될 시각이며, null은 즉시 실행 대상임을 의미한다. {@code version}은 낙관적 락(optimistic lock)이다:
 * InfraManager 호출이 느린 도중 cancel이 CANCELLED를 커밋하면 이 값이 증가하므로, 진행 중(in-flight)인 advance의
 * 낡은(stale) 저장이 종료 상태를 덮어쓰지 않고 거부된다.
 */
@Entity
@Table(
        name = "task",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_pipeline_sequence", columnNames = {"pipeline_id", "sequence"}))
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
