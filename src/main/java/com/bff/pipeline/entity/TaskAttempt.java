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
 * task의 재시도 시도(retry attempt)당 하나의 행(row)을 저장하는 <b>쓰기 전용 관찰(write-only observation)</b>
 * 테이블이다(ADR-016 §3). 엔진은 이 테이블을 절대 읽지 않으며, 이 데이터를 잃더라도 디버그 가능성(debuggability)만
 * 손실될 뿐 정확성(correctness)에는 영향이 없다. 시도가 시작되는 순간
 * {@code attemptNumber = task.failCount + 1}이므로, 두 번 재시도한 후 실패한 task는 세 개의 행(1, 2, 3)을 남긴다.
 * {@code status}는 시도의 결과(outcome)이다: 실행 중에는 IN_PROGRESS이며, 이후 DONE, FAILED, 또는 CANCELLED가
 * 된다(cancel 시점에 열려 있던 시도는 CANCELLED로 종료된다).
 *
 * <p>{@code dispatchResponseCode}/{@code dispatchResponseSummary}는 향후 HTTP InfraManager 어댑터에 의해
 * 채워질 필드이며, 그 전까지는 null이다.
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

    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    private ErrorCode errorCode;

    private Integer dispatchResponseCode;
    private String dispatchResponseSummary;

    @Column(nullable = false)
    private Instant startedAt;
    private Instant finishedAt;
}
