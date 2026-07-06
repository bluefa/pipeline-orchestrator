package com.bff.pipeline.service.task.terraform;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.utils.TaskSettingsResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.bff.pipeline.exception.CallFailedException;

/**
 * Terraform 잡을 디스패치하고 완료까지 폴링하는 {@link TaskType} 구현체다(ADR-016 ed97ec0 §5). {@code execute}는 한 번의
 * dispatch가 만들어 낸 {@code N}개 job id를 담은 원시 response(JSON 배열)를 반환하고, 엔진이 이를
 * {@code task_attempt.response}에 기록한다. {@code check}는 최신 attempt의 {@code response}를 Jackson으로 역직렬화해
 * job id 목록을 얻은 뒤, 각 job id의 상태를 {@code InfraManagerClient}로 조회해 그 결과를 task 단위로 집계한다 —
 * task별 실행 타임아웃(execution timeout)의 제약을 받는다.
 *
 * 완료 집계 — 전원 terminal 대기(N개 job, whole-task 재시도). FAILED job을 봤다고 attempt를 바로 접지 않고
 * 모든 job이 terminal이 될 때까지 폴링을 계속한다(설계 §4.4 집계 정책). 재시도는 fresh dispatch라서, 형제 job이
 * 아직 인프라를 변경 중일 때 재dispatch하면 같은 대상에 terraform이 동시 실행되기 때문이다(state lock 충돌·동시 변경).
 * 모든 job이 terminal이고 전부 성공이면 {@code SUCCEEDED}, 하나라도 실패면 재시도 가능한 {@code JOB_FAILED}다.
 * executionTimeout 도달 시 미종결 job이 남아 있으면 — 그때까지 FAILED 관측이 있으면 {@code JOB_FAILED}, 없으면
 * {@code EXECUTION_TIMEOUT}으로 종결한다(원인이 정확한 쪽). 셋 다 재시도 가능이라 실행 경로는 같다.
 *
 * postCheck 관찰(확장 A). attempt가 판정으로 종결되는 turn에는 {@link TerraformResultRecorder}가 finished
 * job들의 result(= terraform log)를 {@code terraform_result}에 남긴다 — 전원 terminal 대기 덕에 정상 종결에서는
 * 모든 job이 행을 얻는다. 기록 실패는 관찰 결손일 뿐 판정을 바꾸지 않는다.
 *
 * response 유실(ADR §3 invariant 3). dispatch는 됐지만 {@code response}가 최신 attempt에 남지 않은 경우(dispatch
 * 뒤 DB 기록 실패나 크래시)에는 곧바로 실패시키지 않는다. 사유를 정확히 로그로 남기고 executionTimeout까지 기다렸다가 재시도
 * 가능한 {@code EXECUTION_TIMEOUT}으로 fallthrough해 멱등 재dispatch한다(failCount 예산을 함께 쓴다). attempt 행
 * 자체가 유실된 경우도 같은 정책이다({@code checkWithoutAttempt}). 반면 {@code response}가
 * 있는데 역직렬화가 안 되면(malformed) 곧바로 실패 처리한다({@code CHECK_ERROR}).
 *
 * 외부가 돌려준 값을 쓸 수 없는 경우(빈 dispatch 응답, null poll 상태)에는 영속/폴링 대신 호출 실패로 처리해
 * ({@code CallFailedException}) 엔진이 재시도하게 한다. 타입 이름 {@link #NAME}은 모든 terraform task 행에 저장된다.
 */
@Slf4j
@Component
public class TerraformTask implements TaskType {

    public static final String NAME = TaskOperation.Mechanism.TERRAFORM_JOB;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> JOB_IDS = new TypeReference<>() { };

    private final InfraManagerClient infraManagerClient;
    private final TerraformResultRecorder resultRecorder;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public TerraformTask(InfraManagerClient infraManagerClient, TerraformResultRecorder resultRecorder,
            PipelineSettings pipelineSettings, Clock clock) {
        this.infraManagerClient = infraManagerClient;
        this.resultRecorder = resultRecorder;
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
            throw new CallFailedException(
                    "InfraManager returned no dispatch response for " + task.getOperation());
        }
        return DispatchResult.withResponse(response);
    }

    @Override
    public TaskProgress check(String target, Task task, TaskAttempt attempt) {
        String response = attempt.getResponse();
        if (response == null || response.isBlank()) {
            log.warn("task {} attempt {}: dispatch response missing after dispatch (lost DB write or crash); "
                    + "waiting for executionTimeout before an idempotent re-dispatch",
                    task.getId(), attempt.getAttemptNumber());
            return progressUntilExecutionTimeout(task);
        }
        List<String> jobIds;
        try {
            jobIds = OBJECT_MAPPER.readValue(response, JOB_IDS);
        } catch (JsonProcessingException exception) {
            log.warn("task {} attempt {}: malformed dispatch response, failing the task: {}",
                    task.getId(), attempt.getAttemptNumber(), exception.getOriginalMessage());
            return TaskProgress.failedTerminal(ErrorCode.CHECK_ERROR,
                    "malformed dispatch response: " + exception.getOriginalMessage());
        }
        if (jobIds == null || jobIds.isEmpty() || jobIds.stream().anyMatch(this::isBlankJobId)) {
            log.warn("task {} attempt {}: dispatch response carried no usable job ids, failing the task",
                    task.getId(), attempt.getAttemptNumber());
            return TaskProgress.failedTerminal(ErrorCode.CHECK_ERROR,
                    "dispatch response carried no usable job ids");
        }
        return aggregate(task, attempt, jobIds);
    }

    private boolean isBlankJobId(String jobId) {
        return jobId == null || jobId.isBlank();
    }

    /** 관찰 유실(현재 attempt 행 자체가 없음) — response 유실과 같은 정책으로 executionTimeout까지 기다렸다가 멱등 재dispatch한다. */
    @Override
    public TaskProgress checkWithoutAttempt(String target, Task task) {
        log.warn("task {}: current attempt row missing while IN_PROGRESS (lost observation); "
                + "waiting for executionTimeout before an idempotent re-dispatch", task.getId());
        return progressUntilExecutionTimeout(task);
    }

    private TaskProgress progressUntilExecutionTimeout(Task task) {
        if (TaskSettingsResolver.isPastDeadline(task, TaskSettingsResolver.resolveExecutionTimeout(task, pipelineSettings), clock)) {
            return TaskProgress.failedRetryable(ErrorCode.EXECUTION_TIMEOUT,
                    "dispatch observation lost (response or attempt row); execution timeout elapsed before the idempotent re-dispatch");
        }
        return TaskProgress.pending(CheckSignal.RUNNING);
    }

    private TaskProgress aggregate(Task task, TaskAttempt attempt, List<String> rawJobIds) {
        // 저장된 response의 중복 job id에 관대하게 — 집계는 유일 id 기준이다(dispatch 경계는 중복을 이미 거부한다).
        Set<String> jobIds = new LinkedHashSet<>(rawJobIds);
        Map<String, TerraformPoll> finished = new LinkedHashMap<>();
        boolean anyFailed = false;
        for (String jobId : jobIds) {
            TerraformPoll poll = infraManagerClient.terraformJobStatus(jobId, task.getOperation());
            if (poll == null) {
                throw new CallFailedException("InfraManager returned no status for job " + jobId);
            }
            if (poll.finished()) {
                finished.put(jobId, poll);
                anyFailed |= !poll.succeeded();
            }
        }
        boolean allFinished = finished.size() == jobIds.size();
        if (!allFinished && !TaskSettingsResolver.isPastDeadline(
                task, TaskSettingsResolver.resolveExecutionTimeout(task, pipelineSettings), clock)) {
            // 전원 terminal 대기 — 실패 job이 있어도 형제 job이 인프라에서 손을 뗄 때까지 판정을 미룬다.
            return TaskProgress.pending(CheckSignal.RUNNING);
        }
        resultRecorder.recordFinishedJobs(task, attempt, finished);
        if (anyFailed) {
            return TaskProgress.failedRetryable(ErrorCode.JOB_FAILED, describeFailedJobs(finished));
        }
        if (allFinished) {
            return TaskProgress.SUCCEEDED;
        }
        return TaskProgress.failedRetryable(ErrorCode.EXECUTION_TIMEOUT, describeUnfinishedJobs(jobIds, finished));
    }

    /** JOB_FAILED의 "왜" — 실패로 관측된 job id를 원인 텍스트로 남긴다(잡별 로그 본문은 terraform_result가 담당). */
    private static String describeFailedJobs(Map<String, TerraformPoll> finished) {
        List<String> failedJobIds = finished.entrySet().stream()
                .filter(entry -> !entry.getValue().succeeded())
                .map(Map.Entry::getKey)
                .toList();
        return "jobs reported FAILED: " + failedJobIds;
    }

    /** EXECUTION_TIMEOUT의 "왜" — 타임아웃 시점까지 종결되지 않은 job id를 원인 텍스트로 남긴다. */
    private static String describeUnfinishedJobs(Set<String> jobIds, Map<String, TerraformPoll> finished) {
        List<String> unfinishedJobIds = jobIds.stream()
                .filter(jobId -> !finished.containsKey(jobId))
                .toList();
        return "jobs not finished at execution timeout: " + unfinishedJobIds;
    }
}
