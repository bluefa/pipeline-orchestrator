package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskCheck;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 재시도 attempt 한 건과 그 폴 요약이다(P5). response는 dispatch가 돌려준 원시 외부 응답 텍스트로, 형식은
 * 해당 task type만의 사적 계약이므로 이 API는 파싱하지 않고 원문 그대로 노출한다. check는 없으면 null이다.
 * failureDetail은 errorCode를 보충하는 실패 원인 텍스트(예외 메시지 등)로, 실패 종결이 아니면 null이다.
 * terraformResults는 이 attempt에서 종결로 관측·기록된 job별 result 메타다(설계 §4.5) — 본문은 싣지 않으며,
 * TERRAFORM_JOB이 아니거나 기록이 없으면 빈 목록이다. 와이어 필드는 snake_case로 직렬화한다.
 * 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record TaskAttemptView(
        @JsonProperty("attempt_number") int attemptNumber,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("error_code") ErrorCode errorCode,
        @JsonProperty("failure_detail") String failureDetail,
        @JsonProperty("response") String response,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("check") TaskCheckView check,
        @JsonProperty("terraform_results") List<TerraformResultSummary> terraformResults) {

    public static TaskAttemptView from(TaskAttempt attempt, TaskCheck check,
            List<TerraformResultSummary> terraformResults) {
        return TaskAttemptView.builder()
                .attemptNumber(attempt.getAttemptNumber())
                .status(attempt.getStatus())
                .errorCode(attempt.getErrorCode())
                .failureDetail(attempt.getFailureDetail())
                .response(attempt.getResponse())
                .startedAt(attempt.getStartedAt())
                .finishedAt(attempt.getFinishedAt())
                .check(check == null ? null : TaskCheckView.from(check))
                .terraformResults(terraformResults)
                .build();
    }
}
