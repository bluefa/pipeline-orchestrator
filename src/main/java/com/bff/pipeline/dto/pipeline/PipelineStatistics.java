package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.StatisticsPeriod;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * 기간 통계다(P2). since = now - period.window() 를 하한으로, createdAt이 기간 내인 pipeline을 status별로 센다.
 * RUNNING은 순간 상태이므로 "기간 내 생성되었고 현재 RUNNING"을 뜻한다(집계 기준 컬럼 = created_at).
 * period는 StatisticsPeriod의 와이어 토큰(1h/1d/7d)으로 직렬화된다(@JsonValue). 나머지 필드는 snake_case.
 */
public record PipelineStatistics(
        @JsonProperty("period") StatisticsPeriod period,
        @JsonProperty("since") Instant since,
        @JsonProperty("running_count") long runningCount,
        @JsonProperty("failed_count") long failedCount,
        @JsonProperty("done_count") long doneCount,
        @JsonProperty("cancelled_count") long cancelledCount,
        @JsonProperty("total_count") long totalCount) {
}
