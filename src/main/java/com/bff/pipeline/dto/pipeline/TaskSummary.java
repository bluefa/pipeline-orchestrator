package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * task 흐름 노드 한 개의 읽기 전용 뷰다(P4의 tasks 목록). kind는 task type 이름(taskName)이며
 * "TERRAFORM_JOB"/"CONDITION_CHECK" 같은 실행 메커니즘 식별자다. errorCode는 FAILED일 때만 채워진다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
public record TaskSummary(
        @JsonProperty("task_id") long taskId,
        @JsonProperty("sequence") int sequence,
        @JsonProperty("kind") String kind,
        @JsonProperty("task_definition") String taskDefinition,
        @JsonProperty("operation") TaskOperation operation,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("fail_count") int failCount,
        @JsonProperty("error_code") ErrorCode errorCode,
        @JsonProperty("consumes_terraform_slot") Boolean consumesTerraformSlot,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt) {

    public static TaskSummary from(Task task) {
        return new TaskSummary(task.getId(), task.getSequence(), task.getTaskName(), task.getTaskDefinition(),
                task.getOperation(), task.getStatus(), task.getFailCount(), task.getErrorCode(),
                task.getConsumesTerraformSlot(), task.getStartedAt(), task.getFinishedAt());
    }
}
