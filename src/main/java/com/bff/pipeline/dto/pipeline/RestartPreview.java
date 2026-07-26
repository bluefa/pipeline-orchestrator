package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;

/**
 * 재시작 미리보기 응답이다(재시작 설계 §3.1). 실행 버튼을 누르기 전에 무엇이 어떻게 돌아갈지 보여준다 —
 * 어떤 task가 왜(origin_status·origin_error_code) 다시 돌아가고 무엇이 생략되는지. 실행과 동일한 검증을
 * 거친 뒤에만 만들어지므로, 미리보기가 성공하면 실행도 (경합이 없는 한) 성공한다. {@code warnings}는
 * 차단이 아닌 안내다(취소/실패 직후의 in-flight Terraform job 안내). 와이어 필드는 snake_case로 직렬화한다.
 */
@Builder
public record RestartPreview(
        @JsonProperty("origin") OriginSummary origin,
        @JsonProperty("resume_from_sequence") int resumeFromSequence,
        @JsonProperty("skipped_tasks") List<SkippedTask> skippedTasks,
        @JsonProperty("tasks_to_run") List<TaskToRun> tasksToRun,
        @JsonProperty("warnings") List<String> warnings) {

    /** 원본 실행 요약 — "원본 N단계 중 어디부터"를 화면이 그릴 재료다. 인접 동형 인자가 있어 {@code @Builder}로 만든다. */
    @Builder
    public record OriginSummary(
            @JsonProperty("pipeline_id") long pipelineId,
            @JsonProperty("type") PipelineType type,
            @JsonProperty("recipe_definition") String recipeDefinition,
            @JsonProperty("status") PipelineStatus status,
            @JsonProperty("total_task_count") long totalTaskCount,
            @JsonProperty("done_task_count") long doneTaskCount) {
    }

    /** 재시작에서 건너뛰는(완료된) 원본 task. */
    public record SkippedTask(
            @JsonProperty("sequence") int sequence,
            @JsonProperty("task_definition") String taskDefinition,
            @JsonProperty("status") TaskStatus status) {
    }

    /** 다시 실행될 task — sequence는 원본 체인 기준이고, origin_*이 "왜 여기부터인지"를 설명한다. 인접 동형 인자가 많아 {@code @Builder}로 만든다. */
    @Builder
    public record TaskToRun(
            @JsonProperty("sequence") int sequence,
            @JsonProperty("task_definition") String taskDefinition,
            @JsonProperty("kind") String kind,
            @JsonProperty("terraform_action") String terraformAction,
            @JsonProperty("origin_task_id") long originTaskId,
            @JsonProperty("origin_status") TaskStatus originStatus,
            @JsonProperty("origin_error_code") ErrorCode originErrorCode,
            @JsonProperty("origin_fail_count") int originFailCount) {
    }
}
