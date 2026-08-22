package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ApprovalChannel;
import com.bff.pipeline.enums.ApprovalStatus;
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
 * 승인 요청 한 건의 전 생애를 담는 행이다(승인 게이트 ADR §결정 4). 게이트 태스크와 1:1이고
 * ({@code task_id} 유니크), 요청이 만들어진 순간부터 승인·반려·만료·취소로 닫힐 때까지 같은 행이 갱신된다.
 *
 * 이 행이 곧 게이트의 상태다. 게이트 태스크를 다음 상태로 보낼지 정하는 입력은 여기 있는
 * {@code status}와 {@code expiresAt} 둘뿐이고, {@code planSummary} 같은 표시용 값은 전이에 관여하지 않는다.
 * 그래서 요약 추출이 실패해도 승인 자체는 정상적으로 동작한다.
 *
 * {@code approverId}/{@code approverName}은 콘솔 계정이나 Slack 사용자에서 오는 외부 유래 값이라 길이를
 * 통제할 수 없다 — 저장 전에 컬럼 길이({@link #APPROVER_LENGTH})로 잘라, 이름 하나가 길다는 이유로 승인
 * 트랜잭션 전체가 롤백되는 일을 막는다(표시용 값이라 잘림이 무해하다).
 *
 * Slack 표시를 맞추기 위한 메타데이터 컬럼은 여기 없다 — 그 표시를 실제로 만드는 코드와 함께 들어오는
 * 편이 낫고, 컬럼 추가는 나중에도 무해하다. 그때도 이 행이 진실이라는 관계는 그대로다: Slack 표시는 이
 * 행의 투영일 뿐이라 표시가 늦거나 실패해도 승인 정합성은 흔들리지 않는다.
 */
@Entity
@Table(
        name = "task_approval",
        uniqueConstraints = @UniqueConstraint(name = "uq_task_approval_task", columnNames = "task_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TaskApproval {

    /** 승인자 식별자·이름 컬럼 길이. 외부 유래 문자열을 저장 전에 이 길이로 자른다. */
    public static final int APPROVER_LENGTH = 64;

    /**
     * plan_summary에 담을 수 있는 최대 바이트. 컬럼은 TEXT(65535바이트)이고, 만드는 쪽이 이 값에 맞춰
     * 목록을 줄인다 — 컬럼과 그 가드가 한 상수를 나눠 써야 컬럼이 바뀔 때 가드가 조용히 어긋나지 않는다.
     */
    public static final int PLAN_SUMMARY_MAX_BYTES = 60_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 요청이 붙은 게이트 태스크. 유니크라 태스크당 요청은 언제나 하나뿐이다. */
    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Convert(converter = ApprovalStatusConverter.class)
    @Column(nullable = false, length = 16)
    private ApprovalStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    /** 요청 시각 + 승인 만료 시간. 디스패치 때 고정되며, 이 시각이 지나면 승인이 이길 수 없다. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "approver_id", length = APPROVER_LENGTH)
    private String approverId;

    @Column(name = "approver_name", length = APPROVER_LENGTH)
    private String approverName;

    /** 승인이 들어온 경로. 표시·감사 전용이지만, 경로가 늘어날 때 insert가 막히지 않도록 varchar로 저장한다. */
    @Convert(converter = ApprovalChannelConverter.class)
    @Column(length = 16)
    private ApprovalChannel channel;

    /**
     * 승인 화면에 보여줄 plan 요약(JSON). 표시 전용이라 전이 판정은 읽지 않으며, 원천 로그가 불완전하면
     * 수치 대신 "검증 불가"가 담긴다. 무제한 외부 문자열이 이 컬럼을 넘겨 게이트 진입을 롤백시키지 않도록,
     * 만드는 쪽에서 목록 길이를 줄여서라도 컬럼 한도 안으로 맞춘다.
     */
    @Column(name = "plan_summary", columnDefinition = "TEXT")
    private String planSummary;

    /**
     * 외부 유래 표시 문자열을 컬럼 길이로 자른다 — 길이 하나 때문에 판정 트랜잭션이 통째로 깨지지 않게.
     *
     * 자를 자리가 이모지 한 글자의 한가운데면 한 칸 앞에서 자른다. 자바 문자열은 이모지를 두 칸에 나눠
     * 담는데 그 사이를 자르면 짝 잃은 반쪽이 남고, DB가 그것을 거절하면 막으려던 바로 그 일 — 이름 하나
     * 때문에 승인 트랜잭션 전체가 롤백되는 일 — 이 그 승인자에게 매번 벌어진다.
     */
    public static String clampApprover(String value) {
        if (value == null || value.length() <= APPROVER_LENGTH) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(APPROVER_LENGTH - 1))
                ? APPROVER_LENGTH - 1
                : APPROVER_LENGTH;
        return value.substring(0, end);
    }
}
