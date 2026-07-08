package com.bff.pipeline.dto.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.dto.NotifyHealthView;
import com.bff.pipeline.dto.TestResult;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.enums.StatisticsPeriod;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 응답 DTO가 BFF swagger 계약대로 snake_case로 직렬화되는지 검증한다(@JsonProperty). 필드명 계약은 소비자(BFF/FE)와의
 * 정본이라 회귀로 깨지지 않게 못 박는다. camelCase 키가 새어 나오면(예: pipelineId) 즉시 실패한다.
 */
class DtoSnakeCaseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void pipelineSummarySerializesSnakeCase() throws Exception {
        PipelineSummary summary = new PipelineSummary(101L, PipelineType.INSTALL, "ts-1", CloudProvider.AWS,
                "AWS_INSTALL_V1", PipelineStatus.RUNNING, 1, 2,
                Instant.parse("2026-07-02T00:00:00Z"), Instant.parse("2026-07-02T00:05:00Z"));

        String json = mapper.writeValueAsString(summary);

        assertThat(json).contains("\"pipeline_id\":101", "\"target_source_id\":\"ts-1\"",
                "\"cloud_provider\":\"AWS\"", "\"recipe_definition\":", "\"done_task_count\":1",
                "\"total_task_count\":2", "\"created_at\":", "\"last_activity_at\":");
        assertThat(json).doesNotContain("pipelineId", "targetSourceId", "cloudProvider", "doneTaskCount");
    }

    @Test
    void pipelineDetailAndNestedTaskSerializeSnakeCase() throws Exception {
        TaskSummary task = new TaskSummary(5L, 0, "TERRAFORM_JOB", "AWS_SERVICE_APPLY_V1", TaskOperation.AWS_SERVICE_TF_APPLY,
                TaskStatus.IN_PROGRESS, 0, null, true, Instant.parse("2026-07-02T00:00:00Z"), null, "manual apply");
        PipelineDetail detail = new PipelineDetail(101L, PipelineType.INSTALL, "ts-1", CloudProvider.AWS,
                "AWS_INSTALL_V1", PipelineStatus.RUNNING, Instant.parse("2026-07-02T00:00:00Z"),
                Instant.parse("2026-07-02T00:05:00Z"), Instant.parse("2026-07-02T00:06:00Z"), true, false,
                0L, 0, 1, 0, 3, 1, 2, List.of(task));

        String json = mapper.writeValueAsString(detail);

        assertThat(json).contains("\"next_due_at\":", "\"cancel_requested\":false", "\"due_lag_millis\":0",
                "\"current_task_sequence\":0", "\"final_task_sequence\":1", "\"current_max_fail_count\":3");
        assertThat(json).contains("\"task_id\":5", "\"consumes_terraform_slot\":true", "\"fail_count\":0",
                "\"description\":\"manual apply\"");
        assertThat(json).doesNotContain("nextDueAt", "cancelRequested", "taskId", "consumesTerraformSlot");
    }

    @Test
    void liveStatisticsSerializesSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(LivePipelineStatistics.builder()
                .runningPipelineCount(2).pendingPipelineCount(5).inProgressTerraformTaskCount(1)
                .terraformSlotCap(20).runningPipelineCap(100).activeClaimCount(3).build());

        assertThat(json).contains("\"running_pipeline_count\":2", "\"pending_pipeline_count\":5",
                "\"in_progress_terraform_task_count\":1",
                "\"terraform_slot_cap\":20", "\"running_pipeline_cap\":100", "\"active_claim_count\":3");
        assertThat(json).doesNotContain("runningPipelineCount", "terraformSlotCap");
    }

    @Test
    void periodStatisticsSerializesTokenAndSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(PipelineStatistics.builder()
                .period(StatisticsPeriod.ONE_DAY).since(Instant.parse("2026-07-01T00:00:00Z"))
                .pendingCount(5).runningCount(1).failedCount(2).doneCount(3).cancelledCount(4).totalCount(15)
                .build());

        assertThat(json).contains("\"period\":\"1d\"", "\"pending_count\":5", "\"running_count\":1",
                "\"failed_count\":2", "\"done_count\":3", "\"cancelled_count\":4", "\"total_count\":15");
        assertThat(json).doesNotContain("ONE_DAY", "runningCount", "totalCount");
    }

    @Test
    void recipePreviewAndStepsSerializeSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(RecipePreview.from(RecipeDefinition.AWS_INSTALL_V1));

        assertThat(json).contains("\"recipe_definition\":\"AWS_INSTALL_V1\"", "\"display_name\":",
                "\"task_definition\":", "\"consumes_terraform_slot\":",
                "\"definition\":", "\"dispatch_api\":", "\"success_policy\":", "\"result_storage\":");
        assertThat(json).doesNotContain("recipeDefinition", "displayName", "consumesTerraformSlot",
                "dispatchApi", "successPolicy", "resultStorage");
    }

    @Test
    void taskDetailEffectiveSettingsSerializeSnakeCase() throws Exception {
        TaskDetail detail = new TaskDetail(5L, 101L, 0, "TERRAFORM_JOB", "AWS_SERVICE_APPLY_V1",
                TaskDefinitionView.from(TaskDefinition.AWS_SERVICE_APPLY_V1),
                TaskOperation.AWS_SERVICE_TF_APPLY, TaskStatus.IN_PROGRESS, 0, null, true,
                Instant.parse("2026-07-02T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"), null, null,
                Duration.ofMinutes(10), Duration.ofMinutes(50), 2, List.of(), "custom note");

        String json = mapper.writeValueAsString(detail);

        assertThat(json).contains("\"effective_polling_interval\":", "\"effective_execution_timeout\":",
                "\"effective_max_fail_count\":2", "\"next_check_at\":",
                "\"definition\":", "\"dispatch_api\":", "\"status_api\":", "\"result_api\":",
                "\"success_policy\":", "\"result_storage\":", "\"description\":\"custom note\"");
        assertThat(json).doesNotContain("effectiveMaxFailCount", "nextCheckAt", "dispatchApi", "statusApi",
                "resultApi", "successPolicy", "resultStorage");
    }

    @Test
    void taskCatalogResponseSerializesSnakeCase() throws Exception {
        TaskCatalogEntry entry = TaskCatalogEntry.from(TaskDefinition.AWS_SERVICE_APPLY_V1);

        String json = mapper.writeValueAsString(new TaskCatalogResponse(List.of(entry)));

        assertThat(json).contains("\"task_definitions\":", "\"name\":\"AWS_SERVICE_APPLY_V1\"", "\"display_name\":",
                "\"description\":", "\"provider\":\"AWS\"", "\"kind\":\"TERRAFORM_JOB\"",
                "\"consumes_terraform_slot\":true");
        assertThat(json).doesNotContain("taskDefinitions", "displayName", "consumesTerraformSlot");
    }

    @Test
    void taskAttemptAndNestedCheckSerializeSnakeCase() throws Exception {
        TaskCheckView check = new TaskCheckView(3, 2, 1, 0, "NOT_MET", Instant.parse("2026-07-02T00:05:00Z"));
        TerraformResultSummary resultSummary = new TerraformResultSummary("j-1", false, true,
                true, "s3://bucket/j-1.log", Instant.parse("2026-07-02T00:05:00Z"));
        TaskAttemptView attempt = new TaskAttemptView(1, TaskStatus.FAILED, ErrorCode.CHECK_ERROR,
                "infra-manager call failed: 503", "{\"jobIds\":[\"j-1\"]}", Instant.parse("2026-07-02T00:00:00Z"),
                Instant.parse("2026-07-02T00:05:00Z"), check, List.of(resultSummary));

        String json = mapper.writeValueAsString(attempt);

        assertThat(json).contains("\"attempt_number\":1", "\"error_code\":\"CHECK_ERROR\"",
                "\"failure_detail\":\"infra-manager call failed: 503\"", "\"started_at\":",
                "\"finished_at\":", "\"call_count\":3", "\"not_met_count\":2", "\"api_error_count\":1",
                "\"call_timeout_count\":0", "\"last_external_status\":\"NOT_MET\"", "\"last_checked_at\":");
        assertThat(json).contains("\"terraform_results\":", "\"job_id\":\"j-1\"", "\"succeeded\":false",
                "\"truncated\":true", "\"has_body\":true", "\"result_path\":\"s3://bucket/j-1.log\"",
                "\"created_at\":");
        // "jobId"는 검사하지 않는다 — 원시 response 문자열의 "jobIds"(외부 payload 원문)에 오검출된다
        assertThat(json).doesNotContain("attemptNumber", "errorCode", "failureDetail", "callCount", "notMetCount",
                "apiErrorCount", "lastExternalStatus", "lastCheckedAt", "terraformResults", "hasBody",
                "resultPath");
    }

    @Test
    void channelUpsertSerializesSnakeCase() throws Exception {
        ChannelUpsert upsert = new ChannelUpsert("#infra-alerts", true,
                "https://hooks.slack.com/services/T0001/B0002/token");

        String json = mapper.writeValueAsString(upsert);

        assertThat(json).contains("\"channel_label\":\"#infra-alerts\"", "\"enabled\":true",
                "\"slack_webhook_url\":");
        assertThat(json).doesNotContain("channelLabel", "slackWebhookUrl");
    }

    @Test
    void channelViewSerializesSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(ChannelView.builder()
                .channelLabel("#infra-alerts").enabled(true).webhookConfigured(true)
                .webhookMasked("https://hooks.slack.com/…/xxxx")
                .updatedAt(Instant.parse("2026-07-09T00:00:00Z")).build());

        assertThat(json).contains("\"channel_label\":\"#infra-alerts\"", "\"enabled\":true",
                "\"webhook_configured\":true", "\"webhook_masked\":", "\"updated_at\":");
        assertThat(json).doesNotContain("channelLabel", "webhookConfigured", "webhookMasked", "updatedAt");
    }

    @Test
    void testResultSerializesSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(new TestResult(false, "channel not configured"));

        assertThat(json).contains("\"delivered\":false", "\"error\":\"channel not configured\"");
    }

    @Test
    void notifyHealthViewSerializesSnakeCase() throws Exception {
        String json = mapper.writeValueAsString(NotifyHealthView.builder()
                .giveUpCount(2)
                .oldestUnnotifiedAt(Instant.parse("2026-07-09T00:00:00Z"))
                .oldestDeliveryPendingAt(Instant.parse("2026-07-09T00:05:00Z"))
                .channelActive(true)
                .build());

        assertThat(json).contains("\"give_up_count\":2", "\"oldest_unnotified_at\":",
                "\"oldest_delivery_pending_at\":", "\"channel_active\":true");
        assertThat(json).doesNotContain("giveUpCount", "oldestUnnotifiedAt", "oldestDeliveryPendingAt",
                "channelActive");
    }

    @Test
    void terraformResultDetailSerializesSnakeCase() throws Exception {
        TerraformResultDetail detail = new TerraformResultDetail(5L, 2, "j-1", false, false,
                "s3://bucket/j-1.log", Instant.parse("2026-07-02T00:05:00Z"), "terraform: error");

        String json = mapper.writeValueAsString(detail);

        assertThat(json).contains("\"task_id\":5", "\"attempt_number\":2", "\"job_id\":\"j-1\"",
                "\"succeeded\":false", "\"truncated\":false", "\"result_path\":\"s3://bucket/j-1.log\"",
                "\"created_at\":", "\"content\":\"terraform: error\"");
        assertThat(json).doesNotContain("taskId", "attemptNumber", "jobId", "resultPath", "createdAt");
    }
}
