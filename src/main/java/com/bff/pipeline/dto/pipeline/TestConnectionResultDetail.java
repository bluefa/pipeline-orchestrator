package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskExternalExecution;
import com.bff.pipeline.entity.TestConnectionResult;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.utils.HttpResponses;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import lombok.Builder;

/**
 * 연결 테스트 전용 상세 응답이다. 고정 실행 version과 마감, 마지막 정상 원문, 별도 마지막 오류 원문을 제공한다.
 * 아직 폴 관측 전이면 진단 필드는 null이다. 큰 본문을 포함하므로 목록이나 Task 상세에 인라인하지 않는다.
 */
@Builder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TestConnectionResultDetail(Long executionId, String requestKey, String executionVersion, Instant deadlineAt,
        String connectionStatus, String lastResponse, HttpResponseDetail.Metadata response,
        Instant lastObservedAt, Instant terminalObservedAt, ErrorCode lastErrorCode, String lastErrorDetail,
        String lastErrorResponse, HttpResponseDetail.Metadata errorResponse, Instant lastErrorAt) {
    public static TestConnectionResultDetail from(TaskExternalExecution execution, TestConnectionResult result) {
        return builder().executionId(execution.getId()).requestKey(execution.getRequestKey()).executionVersion(execution.getExternalExecutionVersion())
                .deadlineAt(execution.getDeadlineAt()).connectionStatus(result.getLastConnectionStatus())
                .lastResponse(result.getLastResponse()).response(normalMetadata(result))
                .lastObservedAt(result.getLastObservedAt()).terminalObservedAt(result.getTerminalObservedAt())
                .lastErrorCode(result.getLastErrorCode()).lastErrorDetail(result.getLastErrorDetail())
                .lastErrorResponse(result.getLastErrorResponse()).errorResponse(errorMetadata(result))
                .lastErrorAt(result.getLastErrorAt()).build();
    }

    public static TestConnectionResultDetail awaitingObservation(TaskExternalExecution execution) {
        return builder().executionId(execution.getId()).requestKey(execution.getRequestKey()).executionVersion(execution.getExternalExecutionVersion())
                .deadlineAt(execution.getDeadlineAt()).build();
    }

    private static HttpResponseDetail.Metadata normalMetadata(TestConnectionResult result) {
        if (result.getLastObservedAt() == null) return null;
        return HttpResponseDetail.Metadata.builder().operation(HttpResponses.TEST_CONNECTION_POLL).statusCode(result.getHttpStatusCode())
                .contentType(result.getResponseContentType()).receivedAt(result.getResponseReceivedAt())
                .truncated(result.isResponseTruncated()).build();
    }

    private static HttpResponseDetail.Metadata errorMetadata(TestConnectionResult result) {
        if (result.getLastErrorOperation() == null) return null;
        return HttpResponseDetail.Metadata.builder().operation(result.getLastErrorOperation()).statusCode(result.getLastErrorHttpStatusCode())
                .contentType(result.getLastErrorContentType()).receivedAt(result.getLastErrorReceivedAt())
                .truncated(result.isLastErrorTruncated()).build();
    }
}
