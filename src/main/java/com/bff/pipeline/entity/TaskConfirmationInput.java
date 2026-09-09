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
 * 추천 기반 입력 Task가 독립적으로 소유하는 불변 등록 입력이다. 생성 시 요청 키와 옵션만 채우고 추천 성공의
 * 원문·검증 맥락·출처를 정상 점유자의 상태 기록 트랜잭션에서 한 번 저장한다. 관찰 정리로 제거하면 안 된다.
 */
@Entity
@Table(name = "task_confirmation_input", uniqueConstraints = {
        @UniqueConstraint(name = "uq_confirmation_input_task", columnNames = "task_id"),
        @UniqueConstraint(name = "uq_confirmation_input_request", columnNames = "request_key")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor(access = AccessLevel.PRIVATE) @Builder
public class TaskConfirmationInput {
    public static final int REQUEST_KEY_LENGTH = 96;
    public static final int CONTEXT_VALUE_LENGTH = 256;
    public static final int DIGEST_LENGTH = 64;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;
    @Column(name = "request_key", nullable = false, updatable = false, length = REQUEST_KEY_LENGTH)
    private String requestKey;
    @Column(name = "apply_nlb_security_group", nullable = false, updatable = false)
    private boolean applyNlbSecurityGroup;
    @Column(name = "recommendation_body", columnDefinition = "LONGTEXT")
    private String recommendationBody;
    @Column(name = "body_digest", length = DIGEST_LENGTH)
    private String bodyDigest;
    @Column(name = "approval_version", length = CONTEXT_VALUE_LENGTH)
    private String approvalVersion;
    @Column(name = "target_generation", length = CONTEXT_VALUE_LENGTH)
    private String targetGeneration;
    @Column(name = "source_attempt_number")
    private Integer sourceAttemptNumber;
    @Column(name = "http_status_code")
    private Integer httpStatusCode;
    @Column(name = "response_content_type", length = TaskAttempt.CONTENT_TYPE_LENGTH)
    private String responseContentType;
    @Column(name = "response_received_at")
    private Instant responseReceivedAt;
    @Column(name = "captured_at")
    private Instant capturedAt;
}
