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
import com.bff.pipeline.model.TerraformJobRef;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.utils.TaskSettingsResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.exception.CallTimeoutException;

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
 * 빈 dispatch 응답은 영속/폴링 대신 호출 실패로 처리해({@code CallFailedException}) 엔진이 재dispatch하게 한다.
 * 폴 단계의 호출 실패(전송 실패·타임아웃·null poll 상태)는 예외로 던지지 않고 job별 누적으로 흡수한다 — 임계
 * ({@link #maxPollCallErrors}) 전까지는 미종결로 두고, 임계 이상이면 그 job만 실패로 확정한다(아래 폴 집계).
 * 타입 이름 {@link #NAME}은 모든 terraform task 행에 저장된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerraformTask implements TaskType {

    public static final String NAME = TaskOperation.Mechanism.TERRAFORM_JOB;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> JOB_IDS = new TypeReference<>() { };

    /** 폴 호출 누적 실패 임계 기본값. env {@code PIPELINE_MAX_POLL_CALL_ERRORS}(프로퍼티 {@code pipeline.max-poll-call-errors})로 오버라이드한다. */
    public static final int DEFAULT_MAX_POLL_CALL_ERRORS = 10;

    /** 임계 초과로 실패 확정된 job의 합성 판정에 실을 원시 상태 표기(관측된 적 없어 실제 terraformState가 없다). */
    private static final String POLL_UNREACHABLE_STATE = "UNREACHABLE";

    private final InfraManagerClient infraManagerClient;
    private final TerraformResultRecorder resultRecorder;
    private final TerraformJobStateRecorder jobStateRecorder;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    /**
     * 한 job의 폴 호출이 이 횟수만큼 누적 실패하면 그 job을 관측 불능으로 보고 실패로 확정한다. 그 전까지의 전송
     * 실패는 일시 장애로 보고 다음 폴에서 재시도한다. execution-timeout이 더 짧으면(기본 설정처럼 폴 간격 기준
     * 임계 도달 시간이 timeout보다 길면) timeout이 먼저 종결하므로, 이 임계는 폴 간격이 짧게 설정된 task에서 조기
     * 포기로 작동한다. env {@code PIPELINE_MAX_POLL_CALL_ERRORS}로 조정한다.
     */
    @Value("${pipeline.max-poll-call-errors:" + DEFAULT_MAX_POLL_CALL_ERRORS + "}")
    private int maxPollCallErrors;

    /** 임계가 1 미만이면 첫 폴 전에 모든 job을 즉시 실패로 확정하는 오동작이 되므로, 기동 시 검증해 곧바로 실패한다(fail fast). */
    @PostConstruct
    void validateMaxPollCallErrors() {
        if (maxPollCallErrors < 1) {
            throw new IllegalStateException(
                    "pipeline.max-poll-call-errors must be >= 1, was " + maxPollCallErrors);
        }
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
            Optional<TerraformPoll> poll = pollAndObserve(task, attempt, jobId);
            if (poll.isEmpty()) {
                // 임계 미만 전송 실패 — 이번 turn은 미관측이라 미종결로 두고 다음 폴에서 재시도한다(형제 job은 계속 폴).
                continue;
            }
            if (poll.get().finished()) {
                finished.put(jobId, poll.get());
                anyFailed |= !poll.get().succeeded();
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

    /**
     * job 상태를 폴하고 진행-시점 관찰({@code terraform_job_state})을 남긴다. 정상 관측이면 그 폴을 담아 반환한다.
     * 폴 호출이 실패하면(전송 실패·타임아웃·상태 없음) 그 job의 오류를 관찰에 누적하고 job별로 흡수한다 —
     * 누적 호출 실패가 {@link #maxPollCallErrors} 미만이면 일시 장애로 보아 빈 값을 반환해 이번 turn 미관측
     * (미종결)으로 두고, 임계 이상이면 그 job만 관측 불능으로 확정해 FAILED 합성 판정을 담아 반환한다. 어느
     * 경우든 예외를 위로 던지지 않으므로 형제 job은 계속 폴되고 task.failCount는 이 경로로 오르지 않는다
     * (전송 실패는 execution-timeout 또는 임계 확정으로만 판정에 반영된다).
     *
     * 임계를 이미 넘긴 job은 폴하지 않는다 — 형제 대기로 판정이 다음 turn으로 미뤄져도 실패 판정이 유지되도록
     * (sticky) 누적 카운트를 폴 직전에 확인한다. 재폴로 일시 회복이 확정된 실패를 뒤집는 것을 막는다.
     */
    private Optional<TerraformPoll> pollAndObserve(Task task, TaskAttempt attempt, String jobId) {
        TerraformJobRef job = new TerraformJobRef(task, attempt, jobId);
        if (jobStateRecorder.currentCallErrorCount(job) >= maxPollCallErrors) {
            return Optional.of(unreachable(maxPollCallErrors, "previously exhausted"));
        }
        try {
            TerraformPoll poll = infraManagerClient.terraformJobStatus(jobId, task.getOperation());
            if (poll == null) {
                return onPollCallError(job, "InfraManager returned no status for job " + jobId);
            }
            jobStateRecorder.recordObserved(job, poll);
            return Optional.of(poll);
        } catch (CallFailedException | CallTimeoutException callError) {
            return onPollCallError(job, callError.getMessage());
        }
    }

    /**
     * 폴 호출 실패를 job별로 흡수한다 — 오류를 관찰에 누적하고, 누적이 임계 미만이면 미관측(빈 값), 임계 이상이면
     * 그 job을 실패로 확정한 합성 판정을 반환한다. 정상 응답의 FAILED(비즈니스 실패)와 달리 이 경로는 관측 자체를
     * 못 한 job에 대한 것이다.
     */
    private Optional<TerraformPoll> onPollCallError(TerraformJobRef job, String message) {
        int callErrorCount = jobStateRecorder.recordCallError(job, message);
        if (callErrorCount >= maxPollCallErrors) {
            return Optional.of(unreachable(callErrorCount, message));
        }
        return Optional.empty();
    }

    /** 관측 불능으로 확정된 job의 합성 FAILED 판정 — 관측된 적 없어 실제 terraformState가 없다. */
    private static TerraformPoll unreachable(int callErrorCount, String detail) {
        return TerraformPoll.failure(POLL_UNREACHABLE_STATE,
                "poll call failed " + callErrorCount + " times: " + detail);
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
