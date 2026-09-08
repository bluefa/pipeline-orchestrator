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
 * 연결 테스트 Task의 요청 정체성과 고정 마감을 보존한다. 재시도 attempt와 별개의 도메인 데이터이며 모든
 * 재시도는 같은 requestKey와 executionVersion을 사용한다. BLOCKED일 때 마감은 없고 최초 READY에서 설정한다.
 * 관찰 보존 정책으로 지우지 않으며 해당 실행 전체를 정리할 때 함께 제거한다.
 */
@Entity
@Table(name = "task_external_execution", uniqueConstraints = {
        @UniqueConstraint(name = "uq_external_execution_task", columnNames = "task_id"),
        @UniqueConstraint(name = "uq_external_execution_request", columnNames = "request_key")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor(access = AccessLevel.PRIVATE) @Builder
public class TaskExternalExecution {
    public static final int REQUEST_KEY_LENGTH = 128;
    public static final int EXECUTION_VERSION_LENGTH = 128;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;
    @Column(name = "request_key", nullable = false, updatable = false, length = REQUEST_KEY_LENGTH)
    private String requestKey;
    @Column(name = "external_execution_version", length = EXECUTION_VERSION_LENGTH)
    private String externalExecutionVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "deadline_at")
    private Instant deadlineAt;
}
