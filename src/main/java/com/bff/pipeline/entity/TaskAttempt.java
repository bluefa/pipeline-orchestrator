package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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
 * task의 재시도 시도(retry attempt)마다 행 하나를 쌓는 관찰(observation) 테이블이다(ADR-016 §3). 시도가 시작되는
 * 순간 {@code attemptNumber = task.failCount + 1}이므로, 두 번 재시도한 끝에 실패한 task는 행 세 개(1, 2, 3)를
 * 남긴다. {@code status}는 그 시도의 결과(outcome)다 — 실행 중에는 IN_PROGRESS, 끝나면 DONE·FAILED·CANCELLED
 * 중 하나가 된다(cancel 시점에 열려 있던 시도는 CANCELLED로 닫힌다).
 *
 * <p>{@code response}는 dispatch가 돌려준 <b>원시 외부 응답(text)</b>이다(ADR-016 ed97ec0 §3/§5). 엔진은 이 텍스트의
 * 형식을 모른 채 그대로 저장만 하고, 완료 판정 때 각 task 종류({@code TaskType})가 자기 {@code response}를 자기
 * 형식으로 역직렬화한다(응답 스키마는 그 task type만의 사적 계약이다). 완료 판정은 도메인 컬럼이 아니라 이
 * <b>최신 attempt 행의 {@code response}</b>를 입력으로 삼는 {@code check(attempt, task)}가 내린다 — 엔진은 관찰
 * 테이블을 오직 완료 판정에만, 그것도 최신 행 하나만 읽는다(§3 invariant 1). claim·스케줄링·pipeline 전이는 여전히
 * {@code pipeline}/{@code task}만 본다. 최신 {@code response}가 날아가도(dispatch 후 기록 실패) 정확성은 무너지지
 * 않는다 — per-task {@code executionTimeout}이 만료되면 멱등하게 재dispatch되기 때문이다.
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

    /** 이 attempt의 결과(varchar 저장, {@link TaskStatusConverter}). status는 엔진 완료 판정에 직접 쓰이므로 read는 fail-fast를 유지한다. */
    @Convert(converter = TaskStatusConverter.class)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    /** 실패 사유(varchar 저장, {@link ErrorCodeConverter}). 표시용 값이라 미해석 옛 값은 null로 열화한다. */
    @Convert(converter = ErrorCodeConverter.class)
    @Column(length = 32)
    private ErrorCode errorCode;

    @Column(nullable = false)
    private Instant startedAt;
    private Instant finishedAt;
}
