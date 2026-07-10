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
 * terraform job 하나의 진행-시점(in-progress) 관찰 행이다 — {@code terraform_result}(종결 후 로그)가 채우지 못하는
 * 동작 중 상태 가시성을 담는다. attempt가 job 상태를 폴하는 매 turn에, 관측된 job당 이 행이 제자리 upsert된다
 * ((task, attempt, job)당 1행). {@code lastState}는 정규화 전 원시 {@code terraformState} 문자열이고,
 * {@code lastFailReason}은 job이 FAILED로 관측될 때 status 응답이 실어 온 실패 사유이며, {@code lastError}는 폴
 * 호출 자체가 실패했을 때(API_ERROR/CALL_TIMEOUT)의 메시지다 — 셋 다 표시 전용이다.
 *
 * 쓰기 전용 관찰 테이블이라 엔진(claim·스케줄링·상태 전이·완료 판정)은 결코 읽지 않는다(ADR-016 §3 invariant).
 * {@code terraform_result}와 같이 run 단계(tx 밖)에서 best-effort로 쓰이므로, 행/카운트 유실은 진단 손실일 뿐
 * 정합성 손실이 아니다. {@code (task_id, attempt_number, job_id)} 유니크 제약이 재실행(크래시/리스 회수 후 re-poll)
 * 멱등성의 근거다 — 같은 키는 새 행이 아니라 제자리 갱신이다. {@code pollCount}는 그 재실행에 한해 over-count될 수
 * 있는 best-effort 카운터다(상태 자체의 upsert는 멱등).
 */
@Entity
@Table(
        name = "terraform_job_state",
        uniqueConstraints = @UniqueConstraint(name = TerraformJobState.ATTEMPT_JOB_CONSTRAINT,
                columnNames = {"task_id", "attempt_number", "job_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TerraformJobState {

    /** 재실행 멱등성의 근거인 유니크 제약 이름 — recorder가 중복 insert만 골라 삼킬 때 이 이름으로 판별한다. */
    public static final String ATTEMPT_JOB_CONSTRAINT = "uq_terraform_job_state";

    /** 원시 terraformState 컬럼 길이 — 외부 응답값이므로 recorder가 이 길이로 잘라 저장 실패를 막는다. */
    public static final int STATE_LENGTH = 32;

    /** failReason/error 컬럼 길이 — 외부 유래 텍스트이므로 recorder가 이 길이로 잘라 저장 실패를 막는다. */
    public static final int DETAIL_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "job_id", nullable = false, length = 64)
    private String jobId;

    /** 마지막 폴이 관측한 원시 terraformState. 첫 폴이 호출 실패면(상태를 못 얻음) null일 수 있다. */
    @Column(name = "last_state", length = STATE_LENGTH)
    private String lastState;

    /** job이 FAILED로 관측될 때 status 응답의 실패 사유(표시 전용). 그 외에는 null. */
    @Column(name = "last_fail_reason", length = DETAIL_LENGTH)
    private String lastFailReason;

    /** 폴 호출 자체가 실패했을 때의 예외 메시지(표시 전용). 정상 폴에서는 null. */
    @Column(name = "last_error", length = DETAIL_LENGTH)
    private String lastError;

    /**
     * 마지막 정상 폴이 받은 status 응답 body 전문(compact JSON, 전 필드 보존). 상태 필드로는 안 보이는 원본
     * 진단값을 담는다. status 본문은 512를 넘을 수 있어 TEXT로 둔다(terraform_result.result와 같은 결). 폴 호출
     * 자체가 실패한 turn은 직전 원문을 유지한다. 첫 폴이 호출 실패면 null일 수 있다. */
    @Column(name = "last_response", columnDefinition = "TEXT")
    private String lastResponse;

    @Column(name = "poll_count", nullable = false)
    private int pollCount;

    @Column(name = "last_polled_at", nullable = false)
    private Instant lastPolledAt;
}
