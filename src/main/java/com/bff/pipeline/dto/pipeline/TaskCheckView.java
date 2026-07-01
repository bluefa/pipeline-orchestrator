package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TaskCheck;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * attempt 하나의 폴 단계(poll-phase) 요약 관찰값이다(P5). 각 하위 카운터는 왜 아직 완료되지 않았는지를
 * 진단하고, lastExternalStatus는 마지막 폴의 자유형식 디버그 레이블이다. attempt에 폴 요약이 없으면 null이다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
public record TaskCheckView(
        @JsonProperty("call_count") int callCount,
        @JsonProperty("not_met_count") int notMetCount,
        @JsonProperty("api_error_count") int apiErrorCount,
        @JsonProperty("call_timeout_count") int callTimeoutCount,
        @JsonProperty("last_external_status") String lastExternalStatus,
        @JsonProperty("last_checked_at") Instant lastCheckedAt) {

    public static TaskCheckView from(TaskCheck check) {
        return new TaskCheckView(check.getCallCount(), check.getNotMetCount(), check.getApiErrorCount(),
                check.getCallTimeoutCount(), check.getLastExternalStatus(), check.getLastCheckedAt());
    }
}
