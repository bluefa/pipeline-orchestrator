package com.bff.pipeline.entity;

import jakarta.persistence.Column;
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
 * 하나의 시도(attempt)에 대한 폴링 단계(poll-phase) 요약 정보를 나타내는 행(row)이다(ADR-016 §3). 시도당 최대
 * 하나의 행(1:0..1)이며, <b>제자리에서 갱신(updated in place)</b>된다. 따라서 행 수는 폴(poll) 횟수가 아닌
 * 시도 횟수에 따라 증가한다. 쓰기 전용 관찰(write-only observation) 테이블로, 엔진은 이를 절대 읽지 않는다.
 *
 * <p>{@code callCount}는 해당 시도의 진행 중(in-progress) 또는 오류(errored) 폴 관측 횟수를 집계한다(종료
 * 결과 자체는 {@link TaskAttempt}에 저장된다). 하위 카운터들은 근본 원인 분석 질문에 답한다: TTL 만료 조건이
 * NOT_MET인지 아니면 API 실패(API-failed)인지, 그리고 얼마나 많은 폴이 반복(churned)되었는지.
 * {@code lastExternalStatus}는 마지막 폴의 자유 형식 디버그 레이블(예: "RUNNING", "NOT_MET")로,
 * 로직에는 절대 사용되지 않는다. {@code lastResponseCode}/{@code lastResponseSummary}는 향후 HTTP 어댑터에
 * 의해 채워질 필드이다.
 */
@Entity
@Table(
        name = "task_check",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_check_attempt", columnNames = "task_attempt_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TaskCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_attempt_id", nullable = false)
    private Long taskAttemptId;

    @Column(nullable = false)
    private int callCount;
    @Column(nullable = false)
    private int notMetCount;
    @Column(nullable = false)
    private int apiErrorCount;
    @Column(nullable = false)
    private int callTimeoutCount;

    private String lastExternalStatus;
    private Integer lastResponseCode;
    private String lastResponseSummary;
    private Instant lastCheckedAt;
}
