package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskCheck;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * 재시도 attempt 한 건과 그 폴 요약이다(P5). response는 dispatch가 돌려준 원시 외부 응답 텍스트로, 형식은
 * 해당 task type만의 사적 계약이므로 이 API는 파싱하지 않고 원문 그대로 노출한다. check는 없으면 null이다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
public record TaskAttemptView(
        @JsonProperty("attempt_number") int attemptNumber,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("error_code") ErrorCode errorCode,
        @JsonProperty("response") String response,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("check") TaskCheckView check) {

    public static TaskAttemptView from(TaskAttempt attempt, TaskCheck check) {
        return new TaskAttemptView(attempt.getAttemptNumber(), attempt.getStatus(), attempt.getErrorCode(),
                attempt.getResponse(), attempt.getStartedAt(), attempt.getFinishedAt(),
                check == null ? null : TaskCheckView.from(check));
    }
}
