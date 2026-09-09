package com.bff.pipeline.repository;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import java.time.Instant;

/** 상세의 attempt 목록을 위한 DB 투영이다. 기존 소형 response는 유지하고 신규 HTTP 대용량 본문은 읽지 않는다. */
public interface TaskAttemptMetadata {
    Long getId();
    int getAttemptNumber();
    TaskStatus getStatus();
    ErrorCode getErrorCode();
    String getFailureDetail();
    String getResponse();
    Instant getStartedAt();
    Instant getFinishedAt();
    String getHttpOperation();
    Integer getHttpStatusCode();
    String getResponseContentType();
    Instant getResponseReceivedAt();
    Boolean getResponseTruncated();
    Long getConfirmationInputId();
}
