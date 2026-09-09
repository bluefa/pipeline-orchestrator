package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InstallationOperationsClient;
import com.bff.pipeline.client.InstallationOperationsClient.ConfirmationRequest;
import com.bff.pipeline.client.InstallationOperationsClient.Recommendation;
import com.bff.pipeline.client.InstallationOperationsClient.Request;
import com.bff.pipeline.config.InstallationSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.model.ConfirmationInput;
import com.bff.pipeline.model.ConfirmationInput.ExecutionContext;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.HttpExchange;
import com.bff.pipeline.model.HttpTaskResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.utils.HttpResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CSP 공통인 확정정보 삭제와 추천 기반 입력을 실행한다. 입력은 GET을 정상 기록한 뒤 같은 Task의 다음 실행에서
 * POST하며 재시도에서도 이미 고정한 본문을 재사용한다. 이 클래스는 입력을 읽고 외부 호출 결과를 반환할 뿐
 * DB에 쓰지 않는다. 성공/업무 거절은 값이고 통신/계약 오류는 엔진 한 경계가 처리할 통제된 예외다.
 */
@Component
@RequiredArgsConstructor
public class HttpRequestTask implements TaskType {
    public static final String NAME = TaskOperation.Mechanism.HTTP_REQUEST;
    private final InstallationOperationsClient client;
    private final TaskConfirmationInputs inputs;
    private final InstallationSettings settings;
    private final ObjectMapper objectMapper;

    public String taskName() { return NAME; }

    public DispatchResult execute(String target, Task task) {
        return inputs.read(task).map(context -> execute(target, task, context))
                .orElseGet(() -> new DispatchResult.HttpCompleted(invalidInput()));
    }

    private DispatchResult execute(String target, Task task, ExecutionContext context) {
        return validateExecution(task, context).<DispatchResult>map(DispatchResult.HttpCompleted::new)
                .orElseGet(() -> executeValidated(target, task, context));
    }

    private DispatchResult executeValidated(String target, Task task, ExecutionContext context) {
        if (task.getOperation() == TaskOperation.DELETE_CONFIRMED_RESOURCES) {
            return new DispatchResult.HttpCompleted(delete(target, task, context));
        }
        return context.input().<DispatchResult>map(input -> input.body() == null
                ? prepare(target, task, context, input)
                : new DispatchResult.HttpCompleted(confirm(target, task, context, input)))
                .orElseGet(() -> new DispatchResult.HttpCompleted(missingInput()));
    }

    public TaskProgress check(String target, Task task, TaskAttempt attempt) {
        return checkWithoutAttempt(target, task);
    }

    public TaskProgress checkWithoutAttempt(String target, Task task) {
        return inputs.read(task).map(context -> check(target, task, context))
                .orElseGet(() -> new TaskProgress.HttpCompleted(invalidInput()));
    }

    private TaskProgress check(String target, Task task, ExecutionContext context) {
        if (task.getOperation() != TaskOperation.CONFIRM_RESOURCES_FROM_RECOMMENDATION) {
            return new TaskProgress.HttpCompleted(invalidInput());
        }
        HttpTaskResult result = validateExecution(task, context).orElseGet(() -> context.input()
                .map(input -> confirm(target, task, context, input)).orElseGet(HttpRequestTask::missingInput));
        return new TaskProgress.HttpCompleted(result);
    }

    @Override
    public StepOutcome handleCallFailure(Task task, StepOutcome.CallFailure failure) {
        Optional<ConfirmationInput> input = inputs.read(task).flatMap(ExecutionContext::input);
        String operation = task.getOperation() == TaskOperation.DELETE_CONFIRMED_RESOURCES
                ? HttpResponses.CONFIRMATION_DELETE : input.filter(value -> value.body() != null)
                        .map(value -> HttpResponses.CONFIRMATION_POST).orElse(HttpResponses.RECOMMENDATION_GET);
        HttpExchange exchange = failure.exchange() == null ? HttpExchange.builder().operation(operation).build()
                : HttpResponses.bounded(failure.exchange(), operation, settings.httpResponseMaxBytes());
        var result = HttpTaskResult.builder().errorCode(failure.reason()).retryable(failure.retryable())
                .detail(failure.detail()).exchange(exchange);
        input.ifPresent(value -> result.confirmationInputId(value.id()));
        return failure.dispatch() ? new StepOutcome.Dispatched(new DispatchResult.HttpCompleted(result.build()))
                : new StepOutcome.HttpCompleted(result.build());
    }

    private Optional<HttpTaskResult> validateExecution(Task task, ExecutionContext context) {
        if (context.input().filter(input -> !validIdentity(task, context.provider(), input)).isPresent()) {
            return Optional.of(invalidInput());
        }
        if (!client.supports(context.provider(), task.getOperation())) {
            return Optional.of(HttpTaskResult.failed(ErrorCode.OPERATION_UNAVAILABLE, "Installation operation is unavailable"));
        }
        return Optional.empty();
    }

    private boolean validIdentity(Task task, CloudProvider provider, ConfirmationInput input) {
        return TaskConfirmationInputs.requestKey(task).equals(input.requestKey())
                && (!input.applyNlbSecurityGroup() || provider == CloudProvider.AWS);
    }

    private HttpTaskResult delete(String target, Task task, ExecutionContext context) {
        HttpExchange exchange = capture(client.deleteConfirmedResources(request(target, task, context)),
                HttpResponses.CONFIRMATION_DELETE);
        return mutationResult(exchange, 200, null);
    }

    private DispatchResult prepare(String target, Task task, ExecutionContext context, ConfirmationInput input) {
        Recommendation recommendation = client.fetchRecommendation(request(target, task, context));
        HttpExchange exchange = capture(recommendation == null ? null : recommendation.exchange(), HttpResponses.RECOMMENDATION_GET);
        if (exchange.statusCode() == 404) {
            return new DispatchResult.HttpCompleted(failed(ErrorCode.RECOMMENDATION_NOT_FOUND, exchange, input.id()));
        }
        HttpResponses.requireStatus(exchange, 200);
        if (exchange.truncated() || exchange.body() != null
                && exchange.body().getBytes(StandardCharsets.UTF_8).length > settings.confirmationInputMaxBytes()) {
            return new DispatchResult.HttpCompleted(failed(ErrorCode.RESPONSE_TOO_LARGE, exchange, input.id()));
        }
        if (!HttpResponses.validContext(recommendation.context())) {
            return new DispatchResult.HttpCompleted(failed(ErrorCode.EXECUTION_INPUT_INVALID, exchange, input.id()));
        }
        requireObject(exchange);
        return new DispatchResult.HttpPrepared(new Recommendation(exchange, recommendation.context()));
    }

    private HttpTaskResult confirm(String target, Task task, ExecutionContext context, ConfirmationInput input) {
        if (!validInput(input) || !storedBodyIsObject(input.body())) return invalidInput();
        ConfirmationRequest request = ConfirmationRequest.builder().request(request(target, task, context))
                .body(input.body()).context(input.context()).applyNlbSecurityGroup(input.applyNlbSecurityGroup()).build();
        return mutationResult(capture(client.confirmResources(request), HttpResponses.CONFIRMATION_POST), 201, input.id());
    }

    private boolean validInput(ConfirmationInput input) {
        return input.body() != null && input.digest() != null && HttpResponses.validContext(input.context())
                && input.body().getBytes(StandardCharsets.UTF_8).length <= settings.confirmationInputMaxBytes()
                && HttpResponses.digest(input.body()).equals(input.digest())
                && input.requestKey() != null && !input.requestKey().isBlank();
    }

    private boolean storedBodyIsObject(String body) {
        try {
            JsonNode parsed = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            return parsed != null && parsed.isObject();
        } catch (JsonProcessingException malformed) {
            return false;
        }
    }

    private Request request(String target, Task task, ExecutionContext context) {
        return Request.builder().taskId(task.getId()).target(target).provider(context.provider())
                .requestKey(context.input().map(ConfirmationInput::requestKey).orElseGet(() -> deleteRequestKey(task))).build();
    }

    private static String deleteRequestKey(Task task) { return "confirmation-delete:v1:task:" + task.getId(); }

    private HttpTaskResult mutationResult(HttpExchange exchange, int expectedStatus, Long inputId) {
        if (exchange.statusCode() == 409 || exchange.statusCode() == 412) {
            return failed(ErrorCode.CONFIRMATION_CONFLICT, exchange, inputId);
        }
        HttpResponses.requireStatus(exchange, expectedStatus);
        if (exchange.truncated()) return failed(ErrorCode.RESPONSE_TOO_LARGE, exchange, inputId);
        return HttpTaskResult.succeeded(exchange, inputId);
    }

    private HttpExchange capture(HttpExchange response, String operation) {
        return HttpResponses.capture(response, operation, settings.httpResponseMaxBytes());
    }

    private void requireObject(HttpExchange exchange) {
        try {
            JsonNode parsed = exchange.body() == null ? null : objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(exchange.body());
            if (parsed == null || !parsed.isObject()) throw new CallFailedException("Recommendation must be a JSON object", false, exchange);
        } catch (JsonProcessingException malformed) {
            throw new CallFailedException("Recommendation contains invalid JSON", false, exchange);
        }
    }

    private static HttpTaskResult failed(ErrorCode reason, HttpExchange exchange, Long inputId) {
        return HttpTaskResult.builder().errorCode(reason).exchange(exchange).confirmationInputId(inputId).build();
    }
    private static HttpTaskResult missingInput() { return HttpTaskResult.failed(ErrorCode.EXECUTION_INPUT_MISSING, "Confirmation input is missing"); }
    private static HttpTaskResult invalidInput() { return HttpTaskResult.failed(ErrorCode.EXECUTION_INPUT_INVALID, "Confirmation input or execution context is invalid"); }
}
