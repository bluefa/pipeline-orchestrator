package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TerraformJobState;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;

/**
 * terraform job 하나의 진행-시점 상태 전용 조회 응답이다({@link TerraformJobState}) — task 상세 패널에서 특정 job의
 * 상태를 개별 조회할 때 쓴다. attempt 인라인 요약({@link TerraformJobStateSummary})과 달리 (task, attempt, job)를 함께
 * 실어 응답이 스스로를 식별하게 한다. 기존 terraform result 본문 엔드포인트와 대칭이나, 상태는 무거운 본문이 없어
 * lazy가 아니라 바로 담는다. 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 {@code @Builder}로 만든다.
 */
@Builder
public record TerraformJobStateDetail(
        @JsonProperty("task_id") long taskId,
        @JsonProperty("attempt_number") int attemptNumber,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("last_state") String lastState,
        @JsonProperty("last_fail_reason") String lastFailReason,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("poll_count") int pollCount,
        @JsonProperty("last_polled_at") Instant lastPolledAt) {

    public static TerraformJobStateDetail from(TerraformJobState state) {
        return TerraformJobStateDetail.builder()
                .taskId(state.getTaskId())
                .attemptNumber(state.getAttemptNumber())
                .jobId(state.getJobId())
                .lastState(state.getLastState())
                .lastFailReason(state.getLastFailReason())
                .lastError(state.getLastError())
                .pollCount(state.getPollCount())
                .lastPolledAt(state.getLastPolledAt())
                .build();
    }
}
