package com.bff.pipeline.service.task.type;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.utils.TaskSettings;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Terraform 잡을 디스패치하고 완료까지 폴링하는 {@link TaskType} 구현체이다.
 * {@code execute}는 잡을 제출하고 그 핸들(job id)을 task 엔티티에 저장한다.
 * {@code check}는 잡 상태를 읽으며, task별 실행 타임아웃(execution timeout)의 제약을 받는다.
 * 타임아웃이 발생하면 재시도하고, 재디스패치는 새롭고 멱등한 시도이다(ADR-016 §6).
 *
 * <p>외부에서 반환된 값이 사용 불가능한 경우, 영속화하거나 폴링하는 대신 호출 실패로 처리하여
 * 엔진이 재시도하도록 한다. {@code execute}가 반환한 null 또는 빈 job id는 잡이 실제로
 * 시작되지 않았음을 의미하며, {@code check}가 반환한 null 상태는 잡 결과가 아닌 읽기 실패를
 * 의미한다. 두 경우 모두 {@code CallFailedException}을 던져 엔진에 알린다.
 * 타입 이름 {@link #NAME}은 모든 terraform task 행에 저장되며, recipe에서 참조되고
 * registry에 의해 해석된다.
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
    public void execute(String target, Task task) {
        String jobId = infraManager.runTerraform(target, task.getOperation());
        if (jobId == null || jobId.isBlank()) {
            throw new InfraManagerClient.CallFailedException(
                    "InfraManager returned no job id for " + task.getOperation());
        }
        task.setJobId(jobId);
    }

    @Override
    public TaskProgress check(String target, Task task) {
        TerraformPoll poll = infraManager.terraformJobStatus(task.getJobId());
        if (poll == null) {
            throw new InfraManagerClient.CallFailedException(
                    "InfraManager returned no status for job " + task.getJobId());
        }
        if (poll.finished()) {
            return poll.succeeded() ? TaskProgress.SUCCEEDED : TaskProgress.failedRetryable(ErrorCode.JOB_FAILED);
        }
        if (TaskSettings.isPastDeadline(task, TaskSettings.resolveExecutionTimeout(task, settings), clock)) {
            return TaskProgress.failedRetryable(ErrorCode.EXECUTION_TIMEOUT);
        }
        return TaskProgress.pending(CheckSignal.RUNNING);
    }
}
