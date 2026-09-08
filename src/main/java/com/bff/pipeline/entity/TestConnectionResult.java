package com.bff.pipeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.bff.pipeline.enums.ErrorCode;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 한 연결 테스트 실행의 마지막 정상 관찰과 마지막 오류를 각각 보존한다. poll 이력을 무한히 쌓지 않고 같은
 * 행을 갱신하며 종단 관찰은 이후 pending이나 오류로 덮지 않는다. 상태 판정에는 이 진단 행을 읽지 않는다.
 * 본문은 상세 조회에서만 읽고 값의 크기는 외부 호출 결과를 상태 기록 단계에 넘기기 전에 제한한다.
 */
@Entity
@Table(name = "test_connection_result", uniqueConstraints =
        @UniqueConstraint(name = "uq_test_connection_result_execution", columnNames = "external_execution_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor(access = AccessLevel.PRIVATE) @Builder
public class TestConnectionResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "external_execution_id", nullable = false, updatable = false)
    private Long externalExecutionId;
    @Column(length = 16)
    private String lastConnectionStatus;
    @Column(columnDefinition = "longtext")
    private String lastResponse;
    private Integer httpStatusCode;
    @Column(length = TaskAttempt.CONTENT_TYPE_LENGTH)
    private String responseContentType;
    private Instant responseReceivedAt;
    private boolean responseTruncated;
    private Instant lastObservedAt;
    private Instant terminalObservedAt;
    @Convert(converter = ErrorCodeConverter.class) @Column(length = 32)
    private ErrorCode lastErrorCode;
    @Column(length = TaskAttempt.FAILURE_DETAIL_LENGTH)
    private String lastErrorDetail;
    private Instant lastErrorAt;
    @Column(length = TaskAttempt.HTTP_OPERATION_LENGTH)
    private String lastErrorOperation;
    @Column(columnDefinition = "longtext")
    private String lastErrorResponse;
    private Integer lastErrorHttpStatusCode;
    @Column(length = TaskAttempt.CONTENT_TYPE_LENGTH)
    private String lastErrorContentType;
    private Instant lastErrorReceivedAt;
    private boolean lastErrorTruncated;
}
