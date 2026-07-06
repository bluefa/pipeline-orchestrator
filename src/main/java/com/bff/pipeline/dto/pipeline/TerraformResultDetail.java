package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.TerraformResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * terraform job 하나의 저장된 result(= terraform log) 본문 응답이다(P11, 설계 §4.5). task 상세 패널의
 * 메타({@link TerraformResultSummary})에서 "로그 보기"를 눌렀을 때만 lazy 조회된다. {@code content}가 null이면
 * 본문 조회에 실패했던 포인터 행이다 — 행 부재(404)와 구분되는 상태이며, 원본 전문은 {@code resultPath}로
 * 추적한다. {@code truncated}면 16MB 초과분이 tail 우선으로 절단된 본문이다. 와이어 필드는 snake_case로
 * 직렬화한다.
 */
public record TerraformResultDetail(
        @JsonProperty("task_id") long taskId,
        @JsonProperty("attempt_number") int attemptNumber,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("succeeded") boolean succeeded,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("result_path") String resultPath,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("content") String content) {

    public static TerraformResultDetail from(TerraformResult result) {
        return new TerraformResultDetail(result.getTaskId(), result.getAttemptNumber(), result.getJobId(),
                result.isSucceeded(), result.isTruncated(), result.getResultPath(), result.getCreatedAt(),
                result.getResult());
    }
}
