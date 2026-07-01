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
 * 한 시도(attempt)의 폴링 단계(poll-phase)를 요약하는 행이다(ADR-016 §3). 시도당 행은 최대 하나(1:0..1)다.
 * TERRAFORM_JOB 시도는 잡 상태를 여러 번 폴링하므로 그 행이 제자리에서 갱신(updated in place)되고(call_count>1),
 * 행 수는 폴 횟수가 아니라 시도 횟수만큼 늘어난다. CONDITION_CHECK는 폴 한 번이 곧 시도 하나라(ADR-016 §6),
 * 폴마다 새 행이 하나씩 삽입되며(call_count=1) not-met/error 분해는 행을 가로질러 합산하는 진단 집계가 된다.
 * 쓰기 전용 관찰(write-only observation) 테이블이라 엔진은 결코 읽지 않는다.
 *
 * <p>{@code callCount}는 그 시도에서 관측한 진행 중(in-progress)·오류(errored) 폴 횟수를 센다(종료 결과 자체는
 * {@link TaskAttempt}에 남는다). 하위 카운터들은 근본 원인 분석 질문에 답한다 — 조건이 아직 NOT_MET이었는지
 * 아니면 API 실패(API-failed)였는지, 그리고 폴이 얼마나 헛돌았는지(churned). {@code lastExternalStatus}는 마지막
 * 폴의 자유 형식 디버그 레이블(예: "RUNNING", "MET", "NOT_MET")로, 로직에는 절대 쓰지 않는다.
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
    private Instant lastCheckedAt;
}
