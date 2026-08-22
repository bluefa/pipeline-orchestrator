package com.bff.pipeline.entity;

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
 * {@code status}와 {@code expiresAt} 둘뿐이다.
 *
 * 누가 결정했는지(승인자·경로)와 승인 화면에 보여줄 값은 여기 없다 — 그것을 쓰는 코드와 함께 들어오는
 * 편이 낫고, 컬럼 추가는 나중에도 무해하다. 그때도 이 행이 진실이라는 관계는 그대로다.
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

    /** 결정이 확정된 시각. 만료·취소로 닫힐 때도 채워진다. */
    @Column(name = "decided_at")
    private Instant decidedAt;
}
