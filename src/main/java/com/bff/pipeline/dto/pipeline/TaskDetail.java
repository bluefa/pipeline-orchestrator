package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * task 상세 패널이다(P5). task 자체 컬럼 + 유효 설정(오버라이드가 없으면 전역 기본으로 해석한 실제 적용값) +
 * attempt 이력과 각 attempt의 폴 요약을 담는다. effective* 값은 TaskSettingsResolver로 계산한 것으로,
 * task 행의 nullable 오버라이드 원본이 아니라 실제로 적용되는 값이다. executionTimeout은 TERRAFORM_JOB 전용이며,
 * CONDITION_CHECK는 TTL 대신 maxFailCount(재시도 예산)로 경계된다(ADR-016 §6, #15). CONDITION_CHECK는
 * 폴 하나가 곧 attempt 하나라 attempts 목록이 폴 수만큼 늘어난다(각 attempt의 check는 call_count=1).
 * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record TaskDetail(
        @JsonProperty("task_id") long taskId,
        @JsonProperty("pipeline_id") long pipelineId,
        @JsonProperty("sequence") int sequence,
        @JsonProperty("kind") String kind,
        @JsonProperty("task_definition") String taskDefinition,
        @JsonProperty("operation") TaskOperation operation,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("fail_count") int failCount,
        @JsonProperty("error_code") ErrorCode errorCode,
        @JsonProperty("consumes_terraform_slot") Boolean consumesTerraformSlot,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("ready_at") Instant readyAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("next_check_at") Instant nextCheckAt,
        @JsonProperty("effective_polling_interval") Duration effectivePollingInterval,
        @JsonProperty("effective_execution_timeout") Duration effectiveExecutionTimeout,
        @JsonProperty("effective_max_fail_count") int effectiveMaxFailCount,
        @JsonProperty("attempts") List<TaskAttemptView> attempts) {
}
