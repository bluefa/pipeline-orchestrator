package com.bff.pipeline.service;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.utils.TaskKnobs;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * A task that dispatches a Terraform job and polls it to completion. {@code attempt} submits the job
 * and stores its handle; {@code check} reads the job status, bounded by the per-task execution timeout
 * (a timeout retries — the re-dispatch is a fresh, idempotent attempt, ADR-016 §6).
 *
 * <p>An unusable external value is treated as a failed call so the engine retries, rather than being
 * persisted or polled: a null/blank job id from {@code attempt} means the job did not really start, and
 * a null status from {@code check} is a read failure, not a job outcome. The type name {@link #NAME} is
 * stored on every terraform task row (referenced by recipes, resolved by the registry).
 */
@Component
public class TerraformTask implements TaskType {

    public static final String NAME = "TERRAFORM_JOB";

    private final InfraManagerClient infraManager;
    private final PipelineSettings settings;
    private final Clock clock;

    public TerraformTask(InfraManagerClient infraManager, PipelineSettings settings, Clock clock) {
        this.infraManager = infraManager;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public void attempt(String target, Task task) {
        String jobId = infraManager.runTerraform(target, task.getOperation());
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalStateException("InfraManager returned no job id for " + task.getOperation());
        }
        task.setJobId(jobId);
    }

    @Override
    public TaskProgress check(String target, Task task) {
        TerraformPoll poll = infraManager.terraformJobStatus(task.getJobId());
        if (poll == null) {
            throw new IllegalStateException("InfraManager returned no status for job " + task.getJobId());
        }
        if (poll.finished()) {
            return poll.succeeded() ? TaskProgress.SUCCEEDED : TaskProgress.failed(ErrorCode.JOB_FAILED, true);
        }
        if (TaskKnobs.pastDeadline(task, TaskKnobs.executionTimeout(task, settings), clock)) {
            return TaskProgress.failed(ErrorCode.EXECUTION_TIMEOUT, true);
        }
        return TaskProgress.pending(CheckSignal.RUNNING);
    }
}
