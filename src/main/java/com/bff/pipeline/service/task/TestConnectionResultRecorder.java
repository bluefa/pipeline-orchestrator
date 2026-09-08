package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionPollResponse;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskExternalExecution;
import com.bff.pipeline.entity.TestConnectionResult;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.model.HttpExchange;
import com.bff.pipeline.model.HttpTaskResult;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TestConnectionObservation;
import com.bff.pipeline.repository.TaskExternalExecutionRepository;
import com.bff.pipeline.repository.TestConnectionResultRepository;
import com.bff.pipeline.utils.HttpResponses;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소유권이 검증된 트랜잭션에서 연결 테스트의 정상 관찰과 마지막 오류를 따로 저장한다. 종단 정상 관찰은
 * 후속 오류나 대기 상태로 덮지 않는다. Task 전이 결정은 현재 전달받은 typed 결과로만 계산하고 진단 행을
 * 입력으로 사용하지 않는다. 쓰기가 실패하면 Task 상태 기록도 같은 트랜잭션에서 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class TestConnectionResultRecorder {
    private final TaskExternalExecutionRepository executions;
    private final TestConnectionResultRepository results;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public StepOutcome observe(Task task, TestConnectionObservation observation) {
        return executions.findByTaskId(task.getId()).map(execution -> record(execution, observation))
                .orElseGet(() -> new StepOutcome.HttpCompleted(HttpTaskResult.failed(
                        ErrorCode.EXECUTION_INPUT_MISSING, "Test execution disappeared before write-back")));
    }

    private StepOutcome record(TaskExternalExecution execution, TestConnectionObservation observation) {
        return switch (observation) {
            case TestConnectionObservation.Observed observed -> recordNormal(execution, observed.response());
            case TestConnectionObservation.CallFailed failed -> recordCallFailure(execution, failed.failure());
            case TestConnectionObservation.Failed failed -> recordFailure(execution, failed.result());
        };
    }

    private StepOutcome recordNormal(TaskExternalExecution execution, TestConnectionPollResponse response) {
        if (!response.executionVersion().equals(execution.getExternalExecutionVersion())) {
            return recordFailure(execution, HttpTaskResult.builder().errorCode(ErrorCode.EXECUTION_CORRELATION_FAILED)
                    .exchange(response.exchange()).detail("Bound execution changed before write-back").build());
        }
        TestConnectionResult result = current(execution);
        if (result.getTerminalObservedAt() == null) {
            normalResponse(result, response);
            results.save(result);
        }
        return switch (response.status()) {
            case PENDING, RUNNING -> StepOutcome.pending(CheckSignal.RUNNING);
            case SUCCESS -> StepOutcome.succeeded();
            case FAIL -> StepOutcome.failed(ErrorCode.JOB_FAILED, false, "Connection test failed");
        };
    }

    private StepOutcome recordCallFailure(TaskExternalExecution execution, StepOutcome.CallFailure failure) {
        HttpTaskResult result = HttpTaskResult.builder().errorCode(failure.reason()).retryable(false)
                .detail(failure.detail()).exchange(failure.exchange()).build();
        recordFailure(execution, result);
        return failure.retryable() ? StepOutcome.pending(failure.signal()) : new StepOutcome.HttpCompleted(result);
    }

    private StepOutcome recordFailure(TaskExternalExecution execution, HttpTaskResult failure) {
        TestConnectionResult result = current(execution);
        if (result.getTerminalObservedAt() == null) {
            errorResponse(result, failure);
            results.save(result);
        }
        return new StepOutcome.HttpCompleted(failure);
    }

    private TestConnectionResult current(TaskExternalExecution execution) {
        return results.findByExternalExecutionId(execution.getId())
                .orElseGet(() -> TestConnectionResult.builder().externalExecutionId(execution.getId()).build());
    }

    private void normalResponse(TestConnectionResult result, TestConnectionPollResponse response) {
        HttpExchange exchange = response.exchange();
        result.setLastConnectionStatus(response.status().name());
        result.setLastResponse(exchange.body());
        result.setHttpStatusCode(exchange.statusCode());
        result.setResponseContentType(exchange.contentType());
        result.setResponseReceivedAt(exchange.receivedAt());
        result.setResponseTruncated(exchange.truncated());
        result.setLastObservedAt(clock.instant());
        if (response.status().isTerminal()) result.setTerminalObservedAt(clock.instant());
    }

    private void errorResponse(TestConnectionResult result, HttpTaskResult failure) {
        HttpExchange exchange = failure.exchange();
        result.setLastErrorCode(failure.errorCode());
        result.setLastErrorDetail(HttpResponses.clamp(failure.detail(), TaskAttempt.FAILURE_DETAIL_LENGTH));
        result.setLastErrorAt(clock.instant());
        result.setLastErrorOperation(exchange == null ? null : exchange.operation());
        result.setLastErrorResponse(exchange == null ? null : exchange.body());
        result.setLastErrorHttpStatusCode(exchange == null ? null : exchange.statusCode());
        result.setLastErrorContentType(exchange == null ? null : exchange.contentType());
        result.setLastErrorReceivedAt(exchange == null ? null : exchange.receivedAt());
        result.setLastErrorTruncated(exchange != null && exchange.truncated());
    }
}
