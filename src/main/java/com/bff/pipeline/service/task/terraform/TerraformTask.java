package com.bff.pipeline.service.task.terraform;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.model.terraform.JobIdTerraformJob;
import com.bff.pipeline.model.terraform.TerraformJob;
import com.bff.pipeline.utils.TaskSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Terraform 잡을 디스패치하고 완료까지 폴링하는 {@link TaskType} 구현체이다(ADR-016 ed97ec0 §5).
 * {@code execute}는 한 번의 dispatch가 만든 <b>{@code N}개 job id를 담은 원시 response</b>(JSON 배열)를 반환하고,
 * 엔진이 이를 {@code task_attempt.response}에 기록한다. {@code check}는 최신 attempt의 {@code response}를
 * Jackson으로 역직렬화해 {@link TerraformJob} 목록으로 만든 뒤 <b>각 job이 자기 상태를 스스로 조회</b>하게 하고
 * ({@link TerraformJob#pollStatus}), 그 결과를 task 단위로 집계한다 — task별 실행 타임아웃(execution timeout)의 제약을 받는다.
 *
 * <p><b>완료 집계(N개 job, whole-task 재시도).</b> 모든 job이 success로 끝나면 {@code SUCCEEDED}; 하나라도 FAILED면 재시도 가능한
 * {@code JOB_FAILED}; 아직 running이고 executionTimeout 전이면 {@code pending}; executionTimeout을 넘기면
 * 재시도 가능한 {@code EXECUTION_TIMEOUT}이다.
 *
 * <p><b>response 유실(ADR §3 invariant 3).</b> dispatch는 됐으나 {@code response}가 최신 attempt에 남지 않은 경우
 * (dispatch 후 DB 기록 실패/크래시) 즉시 실패시키지 않는다: 사유를 정확히 로그로 남기고 executionTimeout까지 기다린 뒤
 * 재시도 가능한 {@code EXECUTION_TIMEOUT}으로 fallthrough하여 멱등 재dispatch한다(failCount 예산을 공유한다).
 * 반면 {@code response}가 <b>존재하나 역직렬화 불가</b>하면(malformed) 즉시 실패 처리한다({@code CHECK_ERROR}).
 *
 * <p>외부에서 반환된 값이 사용 불가능한 경우(빈 dispatch 응답, null poll 상태)는 영속/폴링 대신 호출 실패로 처리하여
 * ({@code CallFailedException}) 엔진이 재시도하도록 한다. 타입 이름 {@link #NAME}은 모든 terraform task 행에 저장된다.
 */
@Slf4j
@Component
public class TerraformTask implements TaskType {

    public static final String NAME = "TERRAFORM_JOB";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> JOB_IDS = new TypeReference<>() { };

    private final InfraManagerClient infraManagerClient;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public TerraformTask(InfraManagerClient infraManagerClient, PipelineSettings pipelineSettings, Clock clock) {
        this.infraManagerClient = infraManagerClient;
        this.pipelineSettings = pipelineSettings;
        this.clock = clock;
    }

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public DispatchResult execute(String target, Task task) {
        String response = infraManagerClient.runTerraform(target, task.getOperation());
        if (response == null || response.isBlank()) {
            throw new InfraManagerClient.CallFailedException(
                    "InfraManager returned no dispatch response for " + task.getOperation());
        }
        return DispatchResult.withResponse(response);
    }

    @Override
    public TaskProgress check(String target, Task task, TaskAttempt attempt) {
        String response = attempt == null ? null : attempt.getResponse();
        if (response == null || response.isBlank()) {
            return progressForLostResponse(task, attempt);
        }
        List<String> jobIds;
        try {
            jobIds = OBJECT_MAPPER.readValue(response, JOB_IDS);
        } catch (JsonProcessingException exception) {
            log.warn("task {} attempt {}: malformed dispatch response, failing the task: {}",
                    task.getId(), attempt.getAttemptNumber(), exception.getOriginalMessage());
            return TaskProgress.failedTerminal(ErrorCode.CHECK_ERROR);
        }
        if (jobIds == null || jobIds.isEmpty() || jobIds.stream().anyMatch(this::isBlankJobId)) {
            log.warn("task {} attempt {}: dispatch response carried no usable job ids, failing the task",
                    task.getId(), attempt.getAttemptNumber());
            return TaskProgress.failedTerminal(ErrorCode.CHECK_ERROR);
        }
        List<TerraformJob> jobs = jobIds.stream().map(jobId -> (TerraformJob) new JobIdTerraformJob(jobId)).toList();
        return aggregate(task, jobs);
    }

    private boolean isBlankJobId(String jobId) {
        return jobId == null || jobId.isBlank();
    }

    private TaskProgress progressForLostResponse(Task task, TaskAttempt attempt) {
        log.warn("task {} attempt {}: dispatch response missing after dispatch (lost DB write or crash); "
                + "waiting for executionTimeout before an idempotent re-dispatch",
                task.getId(), attempt == null ? -1 : attempt.getAttemptNumber());
        if (TaskSettings.isPastDeadline(task, TaskSettings.resolveExecutionTimeout(task, pipelineSettings), clock)) {
            return TaskProgress.failedRetryable(ErrorCode.EXECUTION_TIMEOUT);
        }
        return TaskProgress.pending(CheckSignal.RUNNING);
    }

    private TaskProgress aggregate(Task task, List<TerraformJob> jobs) {
        boolean allFinished = true;
        for (TerraformJob job : jobs) {
            TerraformPoll poll = job.pollStatus(infraManagerClient);
            if (poll.finished()) {
                if (!poll.succeeded()) {
                    return TaskProgress.failedRetryable(ErrorCode.JOB_FAILED);
                }
            } else {
                allFinished = false;
            }
        }
        if (allFinished) {
            return TaskProgress.SUCCEEDED;
        }
        if (TaskSettings.isPastDeadline(task, TaskSettings.resolveExecutionTimeout(task, pipelineSettings), clock)) {
            return TaskProgress.failedRetryable(ErrorCode.EXECUTION_TIMEOUT);
        }
        return TaskProgress.pending(CheckSignal.RUNNING);
    }
}
