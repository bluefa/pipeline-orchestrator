package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.repository.TerraformResultMetadata;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * attempt 하나에 인라인되는 terraform job result 메타 요약이다(P5, 설계 §4.5). 본문은 싣지 않는다 — 최대
 * 16MB 로그를 상세 패널 JSON에 인라인하면 패널 전체가 그 I/O를 지불하므로, {@code hasBody}로 존재만 알리고
 * 본문은 본문 전용 엔드포인트(P11)가 lazy 조회한다. {@code resultPath}는 InfraManager status 응답이 실어 온
 * 결과 파일 포인터(원본 전문의 추적 경로)로 우리 저장소가 아니다. 와이어 필드는 snake_case로 직렬화한다.
 */
public record TerraformResultSummary(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("succeeded") boolean succeeded,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("has_body") boolean hasBody,
        @JsonProperty("result_path") String resultPath,
        @JsonProperty("created_at") Instant createdAt) {

    public static TerraformResultSummary from(TerraformResultMetadata metadata) {
        return new TerraformResultSummary(metadata.getJobId(), metadata.getSucceeded(), metadata.getTruncated(),
                metadata.getHasBody(), metadata.getResultPath(), metadata.getCreatedAt());
    }
}
