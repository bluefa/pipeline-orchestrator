package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionStartResponse;
import com.bff.pipeline.config.TestConnectionSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskExternalExecution;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.HttpTaskResult;
import com.bff.pipeline.model.TestConnectionExecutionContext;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskExternalExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 테스트 Task의 요청 키, 실행 version, 최초 READY 기준 마감을 보존한다. 입력 읽기는 짧은 읽기
 * 트랜잭션으로 끝내며 쓰기는 생성 또는 소유권을 확인한 상태 기록 트랜잭션에만 참여한다. 재시도는 키와
 * version, 마감을 교체하지 않는다. 공통 Task가 호출할 CSP는 생성 당시 Pipeline 값에서 얻는다.
 */
@Component
@RequiredArgsConstructor
public class TestConnectionExecutionService {
    private final TaskExternalExecutionRepository executions;
    private final PipelineRepository pipelines;
    private final TestConnectionSettings settings;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Task task, Instant readyTime) {
        if (task.getOperation() != TaskOperation.TEST_CONNECTION) return;
        task.setPollingInterval(settings.pollingInterval());
        task.setExecutionTimeout(settings.executionTimeout());
        task.setMaxFailCount(settings.maxFailCount());
        executions.save(TaskExternalExecution.builder().taskId(task.getId()).requestKey(requestKey(task))
                .createdAt(clock.instant()).deadlineAt(readyTime == null ? null : readyTime.plus(task.getExecutionTimeout())).build());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markReady(Task task, Instant readyTime) {
        if (task.getOperation() != TaskOperation.TEST_CONNECTION) return;
        executions.findByTaskId(task.getId()).ifPresent(execution -> {
            if (execution.getDeadlineAt() == null && task.getExecutionTimeout() != null) {
                execution.setDeadlineAt(readyTime.plus(task.getExecutionTimeout()));
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<TestConnectionExecutionContext> read(Task task) {
        return executions.findByTaskId(task.getId())
                .map(execution -> readContext(execution, task.getPipelineId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public HttpTaskResult started(Task task, TestConnectionStartResponse response) {
        return executions.findByTaskId(task.getId()).map(execution -> bind(execution, response))
                .orElseGet(() -> HttpTaskResult.failed(ErrorCode.EXECUTION_INPUT_MISSING, "Test execution is missing"));
    }

    private HttpTaskResult bind(TaskExternalExecution execution, TestConnectionStartResponse response) {
        if (!execution.getRequestKey().equals(response.requestKey()) || !validVersion(response.executionVersion())) {
            return rejectedStart(response, "Start response does not identify the request");
        }
        String boundVersion = execution.getExternalExecutionVersion();
        if (boundVersion != null && !boundVersion.equals(response.executionVersion())) {
            return rejectedStart(response, "Execution version is write-once");
        }
        execution.setExternalExecutionVersion(response.executionVersion());
        return HttpTaskResult.succeeded(response.exchange(), null);
    }

    private HttpTaskResult rejectedStart(TestConnectionStartResponse response, String detail) {
        return HttpTaskResult.builder().errorCode(ErrorCode.EXECUTION_CORRELATION_FAILED)
                .detail(detail).exchange(response.exchange()).build();
    }

    private TestConnectionExecutionContext readContext(TaskExternalExecution execution, Long pipelineId) {
        var context = TestConnectionExecutionContext.builder()
                .requestKey(execution.getRequestKey())
                .executionVersion(execution.getExternalExecutionVersion())
                .deadlineAt(execution.getDeadlineAt());
        pipelines.findById(pipelineId).ifPresent(pipeline ->
                context.provider(pipeline.getCloudProvider()).target(pipeline.getTarget()));
        return context.build();
    }

    public static String requestKey(Task task) { return "test-connection:v1:task:" + task.getId(); }

    public static boolean validVersion(String version) {
        return version != null && !version.isBlank() && version.length() <= TaskExternalExecution.EXECUTION_VERSION_LENGTH;
    }
}
