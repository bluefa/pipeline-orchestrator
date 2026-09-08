package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InstallationOperationsClient;
import com.bff.pipeline.client.InstallationOperationsClient.Request;
import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionStartResponse;
import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionPollResponse;
import com.bff.pipeline.config.InstallationSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.HttpExchange;
import com.bff.pipeline.model.HttpTaskResult;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.model.TestConnectionExecutionContext;
import com.bff.pipeline.model.TestConnectionObservation;
import com.bff.pipeline.utils.HttpResponses;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Task별로 고정한 요청 키로 연결 테스트를 시작하거나 같은 실행을 회수하고 해당 version만 관찰한다.
 * 접수는 완료가 아니며 폴 통신 오류는 같은 attempt를 유지한다. 최초 READY에서 정한 마감 이후에는
 * 추가 호출을 하지 않는다. 이 클래스는 DB에 쓰지 않고 모든 결과를 소유권 확인 후 기록할 값으로 반환한다.
 */
@Component
@RequiredArgsConstructor
public class TestConnectionTask implements TaskType {
    public static final String NAME = TaskOperation.Mechanism.TEST_CONNECTION_JOB;
    private final InstallationOperationsClient client;
    private final TestConnectionExecutionService executions;
    private final InstallationSettings settings;
    private final Clock clock;

    public String taskName() { return NAME; }

    public DispatchResult execute(String target, Task task) {
        return executions.read(task).<DispatchResult>map(context ->
                validate(task, target, context).<DispatchResult>map(DispatchResult.HttpCompleted::new)
                        .orElseGet(() -> start(task, context)))
                .orElseGet(() -> new DispatchResult.HttpCompleted(missingExecution()));
    }

    private DispatchResult start(Task task, TestConnectionExecutionContext context) {
        TestConnectionStartResponse response = client.startOrRecoverTestConnection(request(task, context));
        HttpExchange exchange = capture(response == null ? null : response.exchange(), HttpResponses.TEST_CONNECTION_START, 202);
        if (expired(context)) return new DispatchResult.HttpCompleted(timedOut(exchange));
        if (exchange.truncated()) return new DispatchResult.HttpCompleted(tooLarge(exchange));
        if (!validStart(context, response)) return new DispatchResult.HttpCompleted(correlationFailed(exchange));
        return new DispatchResult.TestConnectionStarted(TestConnectionStartResponse.builder().requestKey(response.requestKey())
                .executionVersion(response.executionVersion()).exchange(exchange).build());
    }

    public TaskProgress check(String target, Task task, TaskAttempt attempt) { return checkWithoutAttempt(target, task); }

    public TaskProgress checkWithoutAttempt(String target, Task task) {
        return executions.read(task).map(context -> validate(task, target, context)
                .map(this::observedFailure).orElseGet(() -> poll(task, context)))
                .orElseGet(() -> observedFailure(missingExecution()));
    }

    private TaskProgress poll(Task task, TestConnectionExecutionContext context) {
        if (!TestConnectionExecutionService.validVersion(context.executionVersion())) {
            return observedFailure(HttpTaskResult.failed(ErrorCode.EXECUTION_INPUT_INVALID, "Bound execution version is missing or invalid"));
        }
        TestConnectionPollResponse response = client.pollTestConnection(request(task, context), context.executionVersion());
        HttpExchange exchange = capture(response == null ? null : response.exchange(), HttpResponses.TEST_CONNECTION_POLL, 200);
        return pollResult(context, response, exchange);
    }

    @Override
    public StepOutcome handleCallFailure(Task task, StepOutcome.CallFailure failure) {
        String operation = failure.dispatch() ? HttpResponses.TEST_CONNECTION_START : HttpResponses.TEST_CONNECTION_POLL;
        HttpExchange exchange = failure.exchange() == null ? HttpExchange.builder().operation(operation).build()
                : HttpResponses.bounded(failure.exchange(), operation, settings.httpResponseMaxBytes());
        StepOutcome.CallFailure bounded = StepOutcome.CallFailure.builder().reason(failure.reason()).signal(failure.signal())
                .dispatch(failure.dispatch()).detail(failure.detail()).retryable(failure.retryable()).exchange(exchange).build();
        if (executions.read(task).filter(context -> context.deadlineAt() != null).filter(this::expired).isPresent()) {
            return failure.dispatch() ? new StepOutcome.Dispatched(new DispatchResult.HttpCompleted(timedOut(exchange)))
                    : new StepOutcome.TestConnectionPolled(new TestConnectionObservation.Failed(timedOut(exchange)));
        }
        if (!failure.dispatch()) return new StepOutcome.TestConnectionPolled(new TestConnectionObservation.CallFailed(bounded));
        return bounded;
    }

    private Optional<HttpTaskResult> validate(Task task, String target, TestConnectionExecutionContext context) {
        if (!validContext(task, target, context)) return Optional.of(HttpTaskResult.failed(ErrorCode.EXECUTION_INPUT_INVALID, "Test execution context is invalid"));
        if (expired(context)) return Optional.of(timedOut(null));
        if (!client.supports(context.provider(), TaskOperation.TEST_CONNECTION)) {
            return Optional.of(HttpTaskResult.failed(ErrorCode.OPERATION_UNAVAILABLE, "Test connection operation is unavailable"));
        }
        return Optional.empty();
    }

    private boolean validContext(Task task, String target, TestConnectionExecutionContext context) {
        return context.provider() != null && context.deadlineAt() != null && context.target() != null
                && context.target().equals(target) && TestConnectionExecutionService.requestKey(task).equals(context.requestKey())
                && (context.executionVersion() == null || TestConnectionExecutionService.validVersion(context.executionVersion()));
    }

    private boolean validStart(TestConnectionExecutionContext context, TestConnectionStartResponse response) {
        return Objects.equals(context.requestKey(), response.requestKey())
                && TestConnectionExecutionService.validVersion(response.executionVersion())
                && (context.executionVersion() == null || context.executionVersion().equals(response.executionVersion()));
    }

    private TaskProgress pollResult(TestConnectionExecutionContext context, TestConnectionPollResponse response, HttpExchange exchange) {
        if (expired(context)) return observedFailure(timedOut(exchange));
        if (exchange.truncated()) return observedFailure(tooLarge(exchange));
        if (!context.executionVersion().equals(response.executionVersion())) return observedFailure(correlationFailed(exchange));
        if (response.status() == null) throw new CallFailedException("Missing test connection status", false, exchange);
        return new TaskProgress.TestConnectionPolled(new TestConnectionObservation.Observed(
                new TestConnectionPollResponse(response.executionVersion(), response.status(), exchange)));
    }

    private TaskProgress observedFailure(HttpTaskResult result) {
        return new TaskProgress.TestConnectionPolled(new TestConnectionObservation.Failed(result));
    }

    private Request request(Task task, TestConnectionExecutionContext context) {
        return Request.builder().taskId(task.getId()).target(context.target()).provider(context.provider()).requestKey(context.requestKey()).build();
    }

    private HttpExchange capture(HttpExchange original, String operation, int expectedStatus) {
        HttpExchange exchange = HttpResponses.capture(original, operation, settings.httpResponseMaxBytes());
        HttpResponses.requireStatus(exchange, expectedStatus);
        return exchange;
    }

    private boolean expired(TestConnectionExecutionContext context) { return !clock.instant().isBefore(context.deadlineAt()); }

    private HttpTaskResult missingExecution() {
        return HttpTaskResult.failed(ErrorCode.EXECUTION_INPUT_MISSING, "Test execution is missing");
    }

    private HttpTaskResult timedOut(HttpExchange exchange) {
        return HttpTaskResult.builder().errorCode(ErrorCode.EXECUTION_TIMEOUT).exchange(exchange)
                .detail("Test execution deadline reached").build();
    }

    private HttpTaskResult tooLarge(HttpExchange exchange) {
        return HttpTaskResult.builder().errorCode(ErrorCode.RESPONSE_TOO_LARGE).exchange(exchange).build();
    }

    private HttpTaskResult correlationFailed(HttpExchange exchange) {
        return HttpTaskResult.builder().errorCode(ErrorCode.EXECUTION_CORRELATION_FAILED).exchange(exchange)
                .detail("Test connection response identifies a different execution").build();
    }
}
