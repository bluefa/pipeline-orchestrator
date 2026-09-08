package com.bff.pipeline.client;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TestConnectionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 요청 키별 실행 회수와 version 지정 관찰을 재현하고 실제 호출이 트랜잭션 밖인지 검사하는 테스트 경계다. */
public class FakeTestConnectionClient extends FakeInstallationOperationsClient {
    public record PollCall(Request request, String executionVersion) { }
    public final List<Request> starts = new ArrayList<>();
    public final List<PollCall> polls = new ArrayList<>();
    public Function<Request, TestConnectionStartResponse> start;
    public BiFunction<Request, String, TestConnectionPollResponse> poll;

    public FakeTestConnectionClient() {
        resetConnectionTest();
    }

    public void resetConnectionTest() {
        reset();
        starts.clear();
        polls.clear();
        start = request -> TestConnectionStartResponse.builder().requestKey(request.requestKey())
                .executionVersion("execution-" + request.taskId()).exchange(response(202, "{ \"success\": true } ")).build();
        poll = (request, version) -> new TestConnectionPollResponse(version, TestConnectionStatus.RUNNING,
                response(200, " {\"connection_status\":\"RUNNING\", \"extra\": true} "));
    }

    @Override
    public boolean supports(CloudProvider provider, TaskOperation operation) {
        return operation == TaskOperation.TEST_CONNECTION ? available && !unsupportedProviders.contains(provider)
                : super.supports(provider, operation);
    }

    @Override
    public TestConnectionStartResponse startOrRecoverTestConnection(Request request) {
        requireOutsideTransaction();
        starts.add(request);
        return start.apply(request);
    }

    @Override
    public TestConnectionPollResponse pollTestConnection(Request request, String version) {
        requireOutsideTransaction();
        polls.add(new PollCall(request, version));
        return poll.apply(request, version);
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("External call ran inside transaction");
    }
}
