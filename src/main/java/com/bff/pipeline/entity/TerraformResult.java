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
 * terraform job 하나의 완료 후 result(= terraform log)를 남기는 postCheck 관찰 행이다(확장 A,
 * docs/terraform-client-and-postcheck-design.md §4.4). attempt가 판정으로 종결되는 turn에, 그 turn에 finished로
 * 관측된 job당 1행이 쓰인다 — 집계 정책(전원 terminal 대기) 덕에 정상 종결에서는 모든 job이 행을 얻는다.
 * 쓰기 전용 관찰 테이블이라 엔진(claim·스케줄링·상태 전이)은 결코 읽지 않으며, 행 유실은 진단 손실일 뿐
 * 정합성 손실이 아니다(ADR-016 §3 invariant).
 *
 * {@code result}는 log 본문이다(MEDIUMTEXT, 16MB 초과분은 tail 우선 절단 + {@code truncated} 표시 — 실패
 * 원인은 로그 끝에 몰린다). 본문 조회에 실패한 job은 {@code result = null}인 포인터 행으로 남는다 —
 * {@code resultPath}(status 응답의 결과 파일 URI)가 원본 전문의 추적 경로다. {@code (taskId, attemptNumber,
 * jobId)} 유니크 제약이 재실행(크래시/리스 회수 후 re-poll) 멱등성의 근거다.
 */
@Entity
@Table(
        name = "terraform_result",
        uniqueConstraints = @UniqueConstraint(name = TerraformResult.ATTEMPT_JOB_CONSTRAINT,
                columnNames = {"task_id", "attempt_number", "job_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TerraformResult {

    /** 재실행 멱등성의 근거인 유니크 제약 이름 — recorder가 중복 insert만 골라 삼킬 때 이 이름으로 판별한다. */
    public static final String ATTEMPT_JOB_CONSTRAINT = "uq_terraform_result_attempt_job";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "job_id", nullable = false, length = 64)
    private String jobId;

    @Column(nullable = false)
    private boolean succeeded;

    @Column(name = "result_path", length = 1024)
    private String resultPath;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String result;

    @Column(nullable = false)
    private boolean truncated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
