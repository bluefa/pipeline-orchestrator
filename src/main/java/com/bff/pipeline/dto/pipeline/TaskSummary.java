package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Builder;

/**
 * task 흐름 노드 한 개의 읽기 전용 뷰다(P4의 tasks 목록). kind는 task type 이름(taskName)이며
 * "TERRAFORM_JOB"/"CONDITION_CHECK" 같은 실행 메커니즘 식별자다. errorCode는 FAILED일 때만 채워진다.
 * description은 custom recipe 실행에서 운영자가 붙인 설명이고 카탈로그 task면 null이다(LIN-18).
 * terraformAction은 operation에서 파생한 표시용 액션 라벨(PLAN/APPLY/DESTROY)이고 terraform이 아닌 task면 null이다.
 * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
 */
@Builder
public record TaskSummary(
        @JsonProperty("task_id") long taskId,
        @JsonProperty("sequence") int sequence,
        @JsonProperty("kind") String kind,
        @JsonProperty("task_definition") String taskDefinition,
        @JsonProperty("operation") TaskOperation operation,
        @JsonProperty("terraform_action") String terraformAction,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("fail_count") int failCount,
        @JsonProperty("error_code") ErrorCode errorCode,
        @JsonProperty("consumes_terraform_slot") Boolean consumesTerraformSlot,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("description") String description) {

    public static TaskSummary from(Task task) {
        TaskOperation operation = task.getOperation();
        return TaskSummary.builder()
                .taskId(task.getId())
                .sequence(task.getSequence())
                .kind(task.getTaskName())
                .taskDefinition(task.getTaskDefinition())
                .operation(operation)
                .terraformAction(operation == null ? null : operation.terraformAction().orElse(null))
                .status(task.getStatus())
                .failCount(task.getFailCount())
                .errorCode(task.getErrorCode())
                .consumesTerraformSlot(task.getConsumesTerraformSlot())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .description(task.getDescription())
                .build();
    }
}
