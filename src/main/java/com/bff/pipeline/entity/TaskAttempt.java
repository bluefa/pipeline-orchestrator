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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * task의 재시도 시도(retry attempt)당 하나의 행(row)을 저장하는 관찰(observation) 테이블이다(ADR-016 §3).
 * 시도가 시작되는 순간 {@code attemptNumber = task.failCount + 1}이므로, 두 번 재시도한 후 실패한 task는
 * 세 개의 행(1, 2, 3)을 남긴다. {@code status}는 시도의 결과(outcome)이다: 실행 중에는 IN_PROGRESS이며,
 * 이후 DONE, FAILED, 또는 CANCELLED가 된다(cancel 시점에 열려 있던 시도는 CANCELLED로 종료된다).
 *
 * <p>{@code response}는 dispatch가 반환한 <b>원시 외부 응답(text)</b>이다(ADR-016 ed97ec0 §3/§5). TERRAFORM_JOB의
 * 경우 한 번의 dispatch가 만든 {@code N}개 job id를 담으며, 각 task 종류({@code TaskType})가 자기 {@code response}를
 * 역직렬화한다. 완료 판정은 도메인 컬럼이 아니라 이 <b>최신 attempt 행의 {@code response}</b>를 입력으로 하는
 * {@code check(attempt, task)}로 이뤄진다 — 엔진은 관찰 테이블을 오직 완료 목적으로, 그것도 최신 행만 읽는다
 * (§3 invariant 1). claim/스케줄링/pipeline 전이는 여전히 {@code pipeline}/{@code task}만 본다. 최신 {@code response}가
 * 유실되면(dispatch 후 기록 실패) 정확성이 깨지지 않는다: per-task {@code executionTimeout}이 만료되어 멱등 재dispatch된다.
 */
@Entity
@Table(
        name = "task_attempt",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_attempt", columnNames = {"task_id", "attempt_number"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TaskAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(columnDefinition = "text")
    private String response;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    private ErrorCode errorCode;

    @Column(nullable = false)
    private Instant startedAt;
    private Instant finishedAt;
}
