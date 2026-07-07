package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TerraformJobState;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;

/**
 * attempt 하나에 인라인되는 terraform job별 진행-시점 상태 요약이다(P5, {@link TerraformJobState}). {@code lastState}는
 * 정규화 전 원시 terraformState, {@code lastFailReason}은 job FAILED 관측의 실패 사유, {@code lastError}는 폴 호출
 * 실패 메시지다(셋 다 없으면 null). 종결 후 로그 본문은 여기 없다 — {@code terraform_result}(per-job 로그)가 담당한다.
 * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record TerraformJobStateSummary(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("last_state") String lastState,
        @JsonProperty("last_fail_reason") String lastFailReason,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("poll_count") int pollCount,
        @JsonProperty("last_polled_at") Instant lastPolledAt) {

    public static TerraformJobStateSummary from(TerraformJobState state) {
        return TerraformJobStateSummary.builder()
                .jobId(state.getJobId())
                .lastState(state.getLastState())
                .lastFailReason(state.getLastFailReason())
                .lastError(state.getLastError())
                .pollCount(state.getPollCount())
                .lastPolledAt(state.getLastPolledAt())
                .build();
    }
}
