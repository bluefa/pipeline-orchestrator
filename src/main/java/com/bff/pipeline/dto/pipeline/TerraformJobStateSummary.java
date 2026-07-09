package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.repository.TerraformJobStateMetadata;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;

/**
 * attempt 하나에 인라인되는 terraform job별 진행-시점 상태 요약이다(P5). {@code lastState}는 정규화 전 원시
 * terraformState, {@code lastFailReason}은 job FAILED 관측의 실패 사유, {@code lastError}는 폴 호출 실패 메시지다
 * (셋 다 없으면 null). 응답 원문({@code last_response})과 종결 후 로그 본문은 여기 없다 — 원문은 per-job 상태
 * 엔드포인트가, 로그는 {@code terraform_result}가 담당한다. 그래서 본문 없는 메타 투영
 * ({@link TerraformJobStateMetadata})에서 만든다 — 상세 패널이 원문 I/O를 지불하지 않게. 와이어 필드는
 * snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record TerraformJobStateSummary(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("last_state") String lastState,
        @JsonProperty("last_fail_reason") String lastFailReason,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("poll_count") int pollCount,
        @JsonProperty("last_polled_at") Instant lastPolledAt) {

    public static TerraformJobStateSummary from(TerraformJobStateMetadata metadata) {
        return TerraformJobStateSummary.builder()
                .jobId(metadata.getJobId())
                .lastState(metadata.getLastState())
                .lastFailReason(metadata.getLastFailReason())
                .lastError(metadata.getLastError())
                .pollCount(metadata.getPollCount())
                .lastPolledAt(metadata.getLastPolledAt())
                .build();
    }
}
