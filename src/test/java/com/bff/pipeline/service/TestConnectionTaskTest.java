package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.client.FakeTestConnectionClient;
import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionPollResponse;
import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionStartResponse;
import com.bff.pipeline.client.InstallationOperationsClient.Recommendation;
import com.bff.pipeline.client.InstallationOperationsClient.VerifiedContext;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.InstallationSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.config.TestConnectionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.controller.PipelineController;
import com.bff.pipeline.controller.GlobalAdvice;
import com.bff.pipeline.dto.pipeline.CustomTaskRequest;
import com.bff.pipeline.dto.pipeline.HttpResponseDetail;
import com.bff.pipeline.dto.pipeline.TestConnectionResultDetail;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskExternalExecution;
import com.bff.pipeline.entity.TestConnectionResult;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.enums.TestConnectionStatus;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.exception.InstallationDataNotFoundException;
import com.bff.pipeline.exception.InstallationRequestException;
import com.bff.pipeline.exception.OrchestrationErrorCode;
import com.bff.pipeline.exception.TaskNotFoundException;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TestConnectionObservation;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskConfirmationInputRepository;
import com.bff.pipeline.repository.TaskExternalExecutionRepository;
import com.bff.pipeline.repository.TestConnectionResultRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineRestarter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.lifecycle.InstallationOperationAvailability;
import com.bff.pipeline.service.query.PipelineQueryService;
import com.bff.pipeline.service.task.ConditionCheckTask;
import com.bff.pipeline.service.task.HttpRequestTask;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskConfirmationInputs;
import com.bff.pipeline.service.task.TaskStateMachine;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.TestConnectionTask;
import com.bff.pipeline.service.task.TestConnectionExecutionService;
import com.bff.pipeline.service.task.TestConnectionResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformJobStateRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 생성·점유·외부 호출·상태 기록 경계를 거쳐 연결 테스트의 동일 실행 회수와 최초 READY 마감을 검증한다.
 * 응답 지연, 폴 오류, 취소와 오래된 점유자 결과를 재현하며 정상/오류 원문 보존 및 관찰 유실 복구도 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class, TaskStateMachine.class,
        TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class,
        ConditionCheckTask.class, HttpRequestTask.class, TaskConfirmationInputs.class, ObservationRecorder.class,
        TaskCanceller.class, PipelineCreator.class, PipelineInserter.class, PipelineControl.class, PipelineRestarter.class, RecipeCatalog.class,
        InstallationOperationAvailability.class, PipelineQueryService.class, TestConnectionTask.class,
        TestConnectionExecutionService.class, TestConnectionResultRecorder.class, TestConnectionTaskTest.Wiring.class})
class TestConnectionTaskTest {
    private static final Instant START = Instant.parse("2026-09-08T00:00:00Z");
    @Autowired PipelineCreator creator;
    @Autowired PipelineWorker worker;
    @Autowired PipelineClaimer claimer;
    @Autowired StepRunner runner;
    @Autowired StepReporter reporter;
    @Autowired PipelineControl control;
    @Autowired PipelineRestarter restarter;
    @Autowired PipelineQueryService queries;
    @Autowired TestConnectionResultRecorder recorder;
    @Autowired FakeTestConnectionClient client;
    @Autowired FakeInfraManagerClient infraManager;
    @Autowired MutableClock clock;
    @Autowired PipelineRepository pipelines;
    @Autowired TaskRepository tasks;
    @Autowired TaskAttemptRepository attempts;
    @Autowired TaskCheckRepository checks;
    @Autowired TaskConfirmationInputRepository inputs;
    @Autowired TaskExternalExecutionRepository executions;
    @Autowired TestConnectionResultRepository results;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void reset() {
        clock.set(START);
        client.resetConnectionTest();
        infraManager.onCloudProvider(CloudProvider.AWS);
    }

    @AfterEach
    void clean() {
        checks.deleteAll();
        attempts.deleteAll();
        results.deleteAll();
        executions.deleteAll();
        inputs.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void commonTaskStartsAndObservesOnlyItsVersionForEveryProvider(CloudProvider provider) {
        infraManager.onCloudProvider(provider);
        Pipeline pipeline = start();
        Task task = task(pipeline);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.RUNNING);
        assertThat(client.polls).isEmpty();
        assertThat(execution(task).getExternalExecutionVersion()).isEqualTo("execution-" + task.getId());
        succeedNextPoll();
        poll();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.polls).singleElement().satisfies(call -> {
            assertThat(call.executionVersion()).isEqualTo(execution(task).getExternalExecutionVersion());
            assertThat(call.request().provider()).isEqualTo(provider);
        });
        assertThat(queries.testConnectionResult(pipeline.getId(), task.getId()).lastResponse()).isEqualTo(" SUCCESS raw ");
        assertThat(pipelines.findById(pipeline.getId()).orElseThrow().getActiveTarget()).isNull();
    }

    @Test
    void catalogTestConnectionUsesTheSameCommonTask() {
        Pipeline pipeline = creator.create("catalog", PipelineType.TEST_CONNECTION);
        assertThat(task(pipeline).getTaskDefinition()).isEqualTo("TEST_CONNECTION_V1");
        assertThat(pipeline.getType()).isEqualTo(PipelineType.TEST_CONNECTION);
    }

    @Test
    void transientPollErrorsKeepTheAttemptAndNormalBodyUntilDeadline() {
        Pipeline pipeline = start();
        poll();
        Task task = task(pipeline);
        String normal = result(task).getLastResponse();
        Instant deadline = execution(task).getDeadlineAt();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, null,
                FakeTestConnectionClient.response(503, " unavailable raw "));
        for (int count = 0; count < 5; count++) poll();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline).getFailCount()).isZero();
        assertThat(client.starts).hasSize(1);
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task.getId())).hasSize(1);
        assertThat(result(task).getLastResponse()).isEqualTo(normal);
        assertThat(result(task).getLastErrorResponse()).isEqualTo(" unavailable raw ");
        assertThat(queries.testConnectionResult(pipeline.getId(), task.getId()).lastErrorResponse()).isEqualTo(" unavailable raw ");
        assertThat(queries.testConnectionResult(pipeline.getId(), task.getId()).errorResponse().statusCode()).isEqualTo(503);
        assertThat(queries.testConnectionResult(pipeline.getId(), task.getId()).requestKey()).isEqualTo(execution(task).getRequestKey());
        assertThat(execution(task).getDeadlineAt()).isEqualTo(deadline);
        clock.set(deadline);
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(result(task).getLastErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(client.starts).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {
        401, 403
    })
    void authorizationPollFailureTerminatesImmediatelyAndPreservesBody(int responseStatus) {
        Pipeline pipeline = start();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, null,
                FakeTestConnectionClient.response(responseStatus, " forbidden "));
        poll();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline).getFailCount()).isZero();
        assertThat(result(task(pipeline)).getLastErrorResponse()).isEqualTo(" forbidden ");
        assertThat(client.starts).hasSize(1);
    }

    @Test
    void executionVersionMismatchDoesNotBorrowAnotherSuccess() {
        Pipeline pipeline = start();
        client.poll = (request, version) -> new TestConnectionPollResponse("different", TestConnectionStatus.SUCCESS,
                FakeTestConnectionClient.response(200, "other success"));
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_CORRELATION_FAILED);
        assertThat(result(task(pipeline)).getLastConnectionStatus()).isNull();
        assertThat(result(task(pipeline)).getLastErrorResponse()).isEqualTo("other success");
    }

    @Test
    void startRetryKeepsItsKeyAndDeadline() {
        Pipeline pipeline = create();
        client.start = request -> {
            throw new CallTimeoutException();
        };
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        Task task = task(pipeline);
        Instant deadline = execution(task).getDeadlineAt();
        client.start = request -> new TestConnectionStartResponse(request.requestKey(), "recovered", FakeTestConnectionClient.response(202, "accepted"));
        poll();
        assertThat(client.starts).hasSize(2);
        assertThat(client.starts.getFirst().requestKey()).isEqualTo(client.starts.getLast().requestKey());
        assertThat(execution(task).getDeadlineAt()).isEqualTo(deadline);
        assertThat(execution(task).getExternalExecutionVersion()).isEqualTo("recovered");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "wrong-key", "oversized-version"
    })
    void invalidStartIdentityIsNeverBound(String invalidPart) {
        Pipeline pipeline = create();
        client.start = request -> new TestConnectionStartResponse(invalidPart.equals("wrong-key") ? "wrong" : request.requestKey(),
                invalidPart.equals("oversized-version") ? "x".repeat(129) : "version", FakeTestConnectionClient.response(202, "raw"));
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_CORRELATION_FAILED);
        assertThat(execution(task(pipeline)).getExternalExecutionVersion()).isNull();
    }

    @Test
    void firstTaskDeadlineStartsAtTheScheduledPipelineStart() {
        Pipeline pipeline = create();
        assertThat(execution(task(pipeline)).getDeadlineAt()).isEqualTo(START.plusSeconds(10).plus(Duration.ofMinutes(50)));
        assertThat(queries.taskDetail(pipeline.getId(), task(pipeline).getId()).effectiveExecutionTimeout()).isEqualTo(Duration.ofMinutes(50));
    }

    @Test
    void aBlockedSuccessorGetsItsOwnFullWindowWhenItBecomesReady() {
        Pipeline pipeline = creator.createCustom("successor", List.of(new CustomTaskRequest("DELETE_CONFIRMED_RESOURCES_V1", null), request()));
        Task successor = chain(pipeline).getLast();
        assertThat(execution(successor).getDeadlineAt()).isNull();
        clock.advance(Duration.ofHours(2));
        worker.pollOnce();
        assertThat(tasks.findById(successor.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(execution(successor).getDeadlineAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(50)));
    }

    @Test
    void repeatedTestTasksHaveIndependentKeysAndReadyWindows() {
        Pipeline pipeline = creator.createCustom("repeat", List.of(request(), request()));
        Task first = chain(pipeline).getFirst();
        Task second = chain(pipeline).getLast();
        assertThat(execution(first).getRequestKey()).isNotEqualTo(execution(second).getRequestKey());
        assertThat(execution(second).getDeadlineAt()).isNull();
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        clock.advance(Duration.ofMinutes(10));
        succeedNextPoll();
        worker.pollOnce();
        assertThat(execution(second).getDeadlineAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(50)));
        assertThat(execution(first).getDeadlineAt()).isEqualTo(START.plusSeconds(10).plus(Duration.ofMinutes(50)));
    }

    @Test
    void missingDeadlineOrExecutionNeverStartsAnotherExternalRun() {
        Pipeline pipeline = create();
        Task task = task(pipeline);
        TaskExternalExecution execution = execution(task);
        execution.setDeadlineAt(null);
        executions.save(execution);
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(client.starts).isEmpty();
    }

    @Test
    void missingExecutionDuringPollingFailsWithoutRedispatch() {
        Pipeline pipeline = start();
        executions.deleteAll();
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_MISSING);
        assertThat(client.starts).hasSize(1);
        assertThat(client.polls).isEmpty();
    }

    @Test
    void missingAttemptAndDiagnosticRowsRecoverFromDomainExecution() {
        Pipeline pipeline = start();
        poll();
        Task task = task(pipeline);
        checks.deleteAll();
        attempts.deleteAll();
        results.deleteAll();
        succeedNextPoll();
        poll();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task.getId())).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE));
        assertThat(result(task).getLastConnectionStatus()).isEqualTo("SUCCESS");
        assertThat(client.starts).hasSize(1);
    }

    @Test
    void startSuccessArrivingAfterDeadlineIsRecordedAsTimeout() {
        Pipeline pipeline = create();
        Instant deadline = execution(task(pipeline)).getDeadlineAt();
        client.start = request -> {
            clock.set(deadline);
            return new TestConnectionStartResponse(request.requestKey(), "late",
                FakeTestConnectionClient.response(202, "late start"));
        };
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(execution(task(pipeline)).getExternalExecutionVersion()).isNull();
        assertThat(queries.httpResponse(pipeline.getId(), task(pipeline).getId(), 1).body()).isEqualTo("late start");
    }

    @Test
    void pollSuccessArrivingAfterDeadlineIsNotAcceptedAsSuccess() {
        Pipeline pipeline = start();
        Instant deadline = execution(task(pipeline)).getDeadlineAt();
        client.poll = (request, version) -> {
            clock.set(deadline);
            return new TestConnectionPollResponse(version,
                TestConnectionStatus.SUCCESS, FakeTestConnectionClient.response(200, "late success"));
        };
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(result(task(pipeline)).getLastErrorResponse()).isEqualTo("late success");
    }

    @Test
    void aStaleStartReportCannotBindTheExternalVersion() {
        Pipeline pipeline = create();
        clock.advance(Duration.ofSeconds(10));
        Claim first = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), task(pipeline), null);
        clock.advance(Duration.ofSeconds(31));
        claimer.claimOneDue().orElseThrow();
        reporter.writeBack(first.pipelineId(), first.token(), outcome);
        assertThat(execution(task(pipeline)).getExternalExecutionVersion()).isNull();
        assertThat(attempts.findAll()).isEmpty();
    }

    @Test
    void cancellationDiscardsAStartResultWithoutClaimingNoExternalEffect() {
        Pipeline pipeline = create();
        clock.advance(Duration.ofSeconds(10));
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), task(pipeline), null);
        control.cancel(pipeline.getId());
        reporter.writeBack(claim.pipelineId(), claim.token(), outcome);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(execution(task(pipeline)).getExternalExecutionVersion()).isNull();
        assertThat(client.starts).hasSize(1);
        assertThat(attempts.findAll()).isEmpty();
    }

    @Test
    void startBindingAndTaskStateRollbackTogether() {
        Pipeline pipeline = create();
        clock.advance(Duration.ofSeconds(10));
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), task(pipeline), null);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            reporter.writeBack(claim.pipelineId(), claim.token(), outcome);
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(execution(task(pipeline)).getExternalExecutionVersion()).isNull();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(attempts.findAll()).isEmpty();
    }

    @Test
    void terminalDiagnosticIsNotOverwrittenByAnOlderPendingObservation() {
        Pipeline pipeline = start();
        succeedNextPoll();
        poll();
        Task task = task(pipeline);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> recorder.observe(task,
                new TestConnectionObservation.Observed(new TestConnectionPollResponse(execution(task).getExternalExecutionVersion(),
                        TestConnectionStatus.PENDING, FakeTestConnectionClient.response(200, "older")))));
        assertThat(result(task).getLastConnectionStatus()).isEqualTo("SUCCESS");
        assertThat(result(task).getLastResponse()).isEqualTo(" SUCCESS raw ");
    }

    @Test
    void resultDetailChecksOwnershipAndDistinguishesAnUnobservedExecution() {
        Pipeline pipeline = create();
        Task task = task(pipeline);
        assertThat(queries.testConnectionResult(pipeline.getId(), task.getId()).executionVersion()).isNull();
        Pipeline other = creator.createCustom("other", List.of(request()));
        assertThatThrownBy(() -> queries.testConnectionResult(other.getId(), task.getId())).isInstanceOf(TaskNotFoundException.class);
        executions.deleteAll();
        assertThatThrownBy(() -> queries.testConnectionResult(pipeline.getId(), task.getId())).isInstanceOf(InstallationDataNotFoundException.class);
    }

    @Test
    void failedConnectionIsATerminalBusinessResult() {
        Pipeline pipeline = start();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, TestConnectionStatus.FAIL,
                FakeTestConnectionClient.response(200, " connection failed "));
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(task(pipeline).getFailCount()).isZero();
        assertThat(result(task(pipeline)).getTerminalObservedAt()).isNotNull();
        assertThat(result(task(pipeline)).getLastResponse()).isEqualTo(" connection failed ");
    }

    @Test
    void pollBodyLimitIsEnforcedAtUtf8Boundaries() {
        Pipeline pipeline = start();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, TestConnectionStatus.SUCCESS,
                FakeTestConnectionClient.response(200, "가".repeat(400000)));
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.RESPONSE_TOO_LARGE);
        assertThat(result(task(pipeline)).isLastErrorTruncated()).isTrue();
        assertThat(result(task(pipeline)).getLastErrorResponse()).hasSize(349525);
    }

    @Test
    void aLargeErrorBodyPreservesRetryabilityAndThePreviousNormalResponse() {
        Pipeline pipeline = start();
        poll();
        String normal = result(task(pipeline)).getLastResponse();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, null,
                FakeTestConnectionClient.response(503, "x".repeat(1100000)));
        poll();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(result(task(pipeline)).getLastResponse()).isEqualTo(normal);
        assertThat(result(task(pipeline)).getLastErrorResponse()).hasSize(1048576);
        assertThat(result(task(pipeline)).isLastErrorTruncated()).isTrue();
    }

    @Test
    void runtimeCapabilityLossStopsBeforeAnotherExternalCall() {
        Pipeline pipeline = start();
        client.available = false;
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.OPERATION_UNAVAILABLE);
        assertThat(result(task(pipeline)).getLastErrorCode()).isEqualTo(ErrorCode.OPERATION_UNAVAILABLE);
        assertThat(client.polls).isEmpty();
    }

    @Test
    void malformedStoredVersionDoesNotInvokeTheClient() {
        Pipeline pipeline = start();
        TaskExternalExecution execution = execution(task(pipeline));
        execution.setExternalExecutionVersion(" ");
        executions.save(execution);
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(result(task(pipeline)).getLastErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(client.polls).isEmpty();
    }

    @Test
    void anUnsupportedEncodingCannotHidePastTheMetadataLimit() {
        Pipeline pipeline = start();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, TestConnectionStatus.SUCCESS,
                FakeTestConnectionClient.response(200, "raw").toBuilder()
                        .contentType("application/json; padding=" + "x".repeat(150) + ";charset=ISO-8859-1").build());
        poll();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(result(task(pipeline)).getLastErrorResponse()).isEqualTo("raw");
    }

    @Test
    void stalePollReportCannotWriteDiagnosticOrCompleteTheTask() {
        Pipeline pipeline = start();
        succeedNextPoll();
        clock.advance(Duration.ofSeconds(4));
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runPoll(pipeline);
        clock.advance(Duration.ofSeconds(31));
        claimer.claimOneDue().orElseThrow();
        reporter.writeBack(claim.pipelineId(), claim.token(), outcome);
        assertThat(results.findAll()).isEmpty();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void cancellationDiscardsPollDiagnosticAndKeepsTheBoundExecution() {
        Pipeline pipeline = start();
        succeedNextPoll();
        clock.advance(Duration.ofSeconds(4));
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runPoll(pipeline);
        control.cancel(pipeline.getId());
        reporter.writeBack(claim.pipelineId(), claim.token(), outcome);
        assertThat(results.findAll()).isEmpty();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(execution(task(pipeline)).getExternalExecutionVersion()).isNotNull();
    }

    @Test
    void pollDiagnosticAndTaskCompletionRollbackTogether() {
        Pipeline pipeline = start();
        succeedNextPoll();
        clock.advance(Duration.ofSeconds(4));
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runPoll(pipeline);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            reporter.writeBack(claim.pipelineId(), claim.token(), outcome);
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(results.findAll()).isEmpty();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(attempts.findByTaskIdAndAttemptNumber(task(pipeline).getId(), 1).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void deadlineBeforeFirstPollPreservesTheAcceptedStartResponse() {
        Pipeline pipeline = start();
        Task task = task(pipeline);
        HttpResponseDetail accepted = queries.httpResponse(pipeline.getId(), task.getId(), 1);
        assertThat(accepted.metadata().operation()).isEqualTo("TEST_CONNECTION_START");
        assertThat(accepted.metadata().receivedAt()).isNotNull();
        clock.set(execution(task).getDeadlineAt());
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(result(task).getLastErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(queries.httpResponse(pipeline.getId(), task.getId(), 1)).isEqualTo(accepted);
        TestConnectionResultDetail detail = queries.testConnectionResult(pipeline.getId(), task.getId());
        assertThat(detail.lastErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(detail.errorResponse()).isNull();
        assertThat(client.polls).isEmpty();
    }

    @Test
    void normalOnlyResultDetailOmitsErrorMetadata() {
        Pipeline pipeline = start();
        poll();
        TestConnectionResultDetail detail = queries.testConnectionResult(pipeline.getId(), task(pipeline).getId());
        assertThat(detail.response().statusCode()).isEqualTo(200);
        assertThat(detail.lastObservedAt()).isNotNull();
        assertThat(detail.lastErrorAt()).isNull();
        assertThat(detail.errorResponse()).isNull();
        assertThat(detail.lastErrorResponse()).isNull();
    }

    @Test
    void errorOnlyResultDetailOmitsNormalMetadata() {
        Pipeline pipeline = start();
        client.poll = (request, version) -> new TestConnectionPollResponse(version, null,
                FakeTestConnectionClient.response(503, "unavailable"));
        poll();
        TestConnectionResultDetail detail = queries.testConnectionResult(pipeline.getId(), task(pipeline).getId());
        assertThat(detail.errorResponse().statusCode()).isEqualTo(503);
        assertThat(detail.lastErrorResponse()).isEqualTo("unavailable");
        assertThat(detail.lastErrorAt()).isNotNull();
        assertThat(detail.lastObservedAt()).isNull();
        assertThat(detail.response()).isNull();
        assertThat(detail.lastResponse()).isNull();
    }

    @Test
    void startCommunicationFailureAfterDeadlineTerminatesWithoutRetry() {
        Pipeline pipeline = create();
        Task task = task(pipeline);
        Instant deadline = execution(task).getDeadlineAt();
        client.start = request -> {
            clock.set(deadline);
            throw new CallTimeoutException();
        };
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(task(pipeline).getFailCount()).isZero();
        assertThat(execution(task).getExternalExecutionVersion()).isNull();
        assertThat(execution(task).getDeadlineAt()).isEqualTo(deadline);
        assertThat(client.starts).hasSize(1);
        assertThat(queries.httpResponse(pipeline.getId(), task.getId(), 1).metadata().operation())
                .isEqualTo("TEST_CONNECTION_START");
    }

    @Test
    void pollCommunicationFailureAfterDeadlineTerminatesTheSameAttempt() {
        Pipeline pipeline = start();
        Task task = task(pipeline);
        String version = execution(task).getExternalExecutionVersion();
        Instant deadline = execution(task).getDeadlineAt();
        client.poll = (request, executionVersion) -> {
            clock.set(deadline);
            throw new CallTimeoutException();
        };
        poll();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(task(pipeline).getFailCount()).isZero();
        assertThat(result(task).getLastErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(execution(task).getExternalExecutionVersion()).isEqualTo(version);
        assertThat(execution(task).getDeadlineAt()).isEqualTo(deadline);
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task.getId())).hasSize(1);
        assertThat(client.starts).hasSize(1);
        assertThat(client.polls).hasSize(1);
        TestConnectionResultDetail detail = queries.testConnectionResult(pipeline.getId(), task.getId());
        assertThat(detail.errorResponse().operation()).isEqualTo("TEST_CONNECTION_POLL");
        assertThat(detail.errorResponse().statusCode()).isNull();
        assertThat(detail.errorResponse().receivedAt()).isNull();
        assertThat(detail.lastErrorResponse()).isNull();
    }

    @Test
    void startRetryBudgetExhaustionKeepsTheOriginalUnboundExecution() {
        Pipeline pipeline = create();
        Task task = task(pipeline);
        TaskExternalExecution original = execution(task);
        client.start = request -> new TestConnectionStartResponse(request.requestKey(), "must-not-bind",
                FakeTestConnectionClient.response(503, "busy"));
        clock.advance(Duration.ofSeconds(10));
        for (int attempt = 0; attempt < 24; attempt++) {
            worker.pollOnce();
            clock.advance(Duration.ofSeconds(4));
        }
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(task(pipeline).getFailCount()).isEqualTo(24);
        assertThat(client.starts).hasSize(24).allSatisfy(request ->
                assertThat(request.requestKey()).isEqualTo(original.getRequestKey()));
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task.getId())).hasSize(24);
        assertThat(execution(task).getExternalExecutionVersion()).isNull();
        assertThat(execution(task).getRequestKey()).isEqualTo(original.getRequestKey());
        assertThat(execution(task).getDeadlineAt()).isEqualTo(original.getDeadlineAt());
        assertThat(client.polls).isEmpty();
    }

    @Test
    void missingDeadlineDuringPollingFailsWithoutReinitialization() {
        Pipeline pipeline = start();
        Task task = task(pipeline);
        TaskExternalExecution execution = execution(task);
        String version = execution.getExternalExecutionVersion();
        execution.setDeadlineAt(null);
        executions.save(execution);
        poll();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(result(task).getLastErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(execution(task).getDeadlineAt()).isNull();
        assertThat(execution(task).getExternalExecutionVersion()).isEqualTo(version);
        assertThat(client.starts).hasSize(1);
        assertThat(client.polls).isEmpty();
    }

    @Test
    void restartCreatesANewConnectionIdentityAndDeadlineWithoutCopyingTheVersion() {
        Pipeline origin = start();
        Task originTask = task(origin);
        TaskExternalExecution original = execution(originTask);
        control.cancel(origin.getId());
        clock.advance(Duration.ofMinutes(10));
        assertThat(restarter.preview(origin.getTarget(), origin.getId(), null).warnings()).hasSize(1)
                .anySatisfy(warning -> assertThat(warning).contains("새 Task와 요청 키"));
        Pipeline restarted = restarter.restart(origin.getTarget(), origin.getId(), null);
        Task restartedTask = task(restarted);
        TaskExternalExecution fresh = execution(restartedTask);
        assertThat(restarted.getOriginPipelineId()).isEqualTo(origin.getId());
        assertThat(restartedTask.getOriginTaskId()).isEqualTo(originTask.getId());
        assertThat(fresh.getRequestKey()).isNotEqualTo(original.getRequestKey());
        assertThat(fresh.getExternalExecutionVersion()).isNull();
        assertThat(fresh.getDeadlineAt()).isEqualTo(clock.instant().plusSeconds(10).plus(Duration.ofMinutes(50)));
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        assertThat(client.starts).hasSize(2);
        assertThat(client.starts.getLast().requestKey()).isEqualTo(fresh.getRequestKey());
        assertThat(execution(originTask).getExternalExecutionVersion()).isEqualTo(original.getExternalExecutionVersion());
        assertThat(status(origin)).isEqualTo(PipelineStatus.CANCELLED);
    }

    @Test
    void restartKeepsTheInputOptionButFetchesAFreshRecommendationWithANewKey() {
        Pipeline origin = creator.createCustom("input-restart", List.of(
                new CustomTaskRequest("CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1", null, true)));
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        Task originTask = task(origin);
        var original = inputs.findByTaskId(originTask.getId()).orElseThrow();
        control.cancel(origin.getId());
        assertThat(restarter.preview(origin.getTarget(), origin.getId(), null).tasksToRun()).singleElement()
                .satisfies(step -> assertThat(step.applyNlbSecurityGroup()).isTrue());
        Pipeline restarted = restarter.restart(origin.getTarget(), origin.getId(), null);
        Task restartedTask = task(restarted);
        var fresh = inputs.findByTaskId(restartedTask.getId()).orElseThrow();
        assertThat(fresh.isApplyNlbSecurityGroup()).isTrue();
        assertThat(fresh.getRequestKey()).isNotEqualTo(original.getRequestKey());
        assertThat(fresh.getRecommendationBody()).isNull();
        client.recommendation = request -> new Recommendation(FakeTestConnectionClient.response(200, "{\"fresh\":true}"),
                new VerifiedContext("new-approval", "new-generation"));
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        poll();
        assertThat(client.confirmations).singleElement().satisfies(request -> {
            assertThat(request.body()).isEqualTo("{\"fresh\":true}");
            assertThat(request.request().requestKey()).isEqualTo(fresh.getRequestKey());
            assertThat(request.applyNlbSecurityGroup()).isTrue();
        });
        assertThat(inputs.findByTaskId(originTask.getId()).orElseThrow().getRecommendationBody())
                .isEqualTo(original.getRecommendationBody());
        assertThat(status(restarted)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void restartAndPreviewRecheckTheCurrentOperationCapability() {
        Pipeline origin = start();
        control.cancel(origin.getId());
        client.available = false;
        assertThatThrownBy(() -> restarter.preview(origin.getTarget(), origin.getId(), null))
                .isInstanceOfSatisfying(InstallationRequestException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrchestrationErrorCode.OPERATION_UNAVAILABLE.code()));
        assertThatThrownBy(() -> restarter.restart(origin.getTarget(), origin.getId(), null))
                .isInstanceOf(InstallationRequestException.class);
        assertThat(pipelines.count()).isEqualTo(1);
    }

    @Test
    void restartRejectsALostInputOptionInsteadOfAssumingFalse() {
        Pipeline origin = creator.createCustom("missing-option", List.of(
                new CustomTaskRequest("CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1", null, true)));
        control.cancel(origin.getId());
        inputs.deleteAll();
        assertThatThrownBy(() -> restarter.preview(origin.getTarget(), origin.getId(), null))
                .isInstanceOf(InstallationDataNotFoundException.class);
        assertThatThrownBy(() -> restarter.restart(origin.getTarget(), origin.getId(), null))
                .isInstanceOf(InstallationDataNotFoundException.class);
        assertThat(pipelines.count()).isEqualTo(1);
    }

    @Test
    void reconfirmationRestartRequiresTheCurrentDeletionOrderContract() {
        Pipeline origin = creator.create("reconfirmation-restart", PipelineType.RECONFIRM);
        control.cancel(origin.getId());
        client.reconfirmationAvailable = false;
        assertThatThrownBy(() -> restarter.preview(origin.getTarget(), origin.getId(), null))
                .isInstanceOfSatisfying(InstallationRequestException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrchestrationErrorCode.OPERATION_UNAVAILABLE.code()));
        assertThatThrownBy(() -> restarter.restart(origin.getTarget(), origin.getId(), null))
                .isInstanceOf(InstallationRequestException.class);
        assertThat(pipelines.count()).isEqualTo(1);
    }

    @Test
    void mixedReconfirmationRestartRetainsBothExternalExecutionWarnings() {
        Pipeline origin = creator.create("mixed-warnings", PipelineType.RECONFIRM);
        control.cancel(origin.getId());
        assertThat(restarter.preview(origin.getTarget(), origin.getId(), null).warnings())
                .hasSize(2)
                .anySatisfy(warning -> assertThat(warning).contains("새 Task와 요청 키"))
                .anySatisfy(warning -> assertThat(warning).contains("Terraform job"));
    }

    @Test
    void httpResponseEndpointReturnsBodyMetadataAndControlledMissingResponse() throws Exception {
        Pipeline pipeline = start();
        Task task = task(pipeline);
        String suffix = "/attempts/1/http-response";
        bodyApi().perform(get(bodyPath(pipeline.getId(), task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.metadata.operation").value("TEST_CONNECTION_START"))
                .andExpect(jsonPath("$.metadata.status_code").value(202))
                .andExpect(jsonPath("$.metadata.content_type").value("application/json; charset=UTF-8"))
                .andExpect(jsonPath("$.metadata.received_at").exists())
                .andExpect(jsonPath("$.body").value("{ \"success\": true } "));
        assertBodyOwnership(pipeline, task, suffix);
        bodyApi().perform(get(bodyPath(pipeline.getId(), task.getId(), "/attempts/2/http-response")))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.code").value(OrchestrationErrorCode.HTTP_RESPONSE_NOT_FOUND.code()));
    }

    @Test
    void confirmationInputEndpointReturnsCapturedInputAndControlledMissingInput() throws Exception {
        Pipeline pipeline = creator.createCustom("wire-input", List.of(
                new CustomTaskRequest("CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1", null, true)));
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        Task task = task(pipeline);
        String suffix = "/confirmation-input";
        bodyApi().perform(get(bodyPath(pipeline.getId(), task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.recommendation_body").value(FakeTestConnectionClient.BODY))
                .andExpect(jsonPath("$.apply_nlb_security_group").value(true))
                .andExpect(jsonPath("$.source_attempt_number").value(1))
                .andExpect(jsonPath("$.response.status_code").value(200))
                .andExpect(jsonPath("$.body_digest").exists());
        assertBodyOwnership(pipeline, task, suffix);
        inputs.deleteAll();
        bodyApi().perform(get(bodyPath(pipeline.getId(), task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.code").value(OrchestrationErrorCode.CONFIRMATION_INPUT_NOT_FOUND.code()));
    }

    @Test
    void connectionResultEndpointReturnsBoundExecutionAndControlledMissingResult() throws Exception {
        Pipeline pipeline = start();
        poll();
        Task task = task(pipeline);
        String suffix = "/test-connection-result";
        bodyApi().perform(get(bodyPath(pipeline.getId(), task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.execution_version").value(execution(task).getExternalExecutionVersion()))
                .andExpect(jsonPath("$.request_key").value(execution(task).getRequestKey()))
                .andExpect(jsonPath("$.deadline_at").exists())
                .andExpect(jsonPath("$.connection_status").value("RUNNING"))
                .andExpect(jsonPath("$.response.status_code").value(200))
                .andExpect(jsonPath("$.last_response").value(result(task).getLastResponse()));
        assertBodyOwnership(pipeline, task, suffix);
        executions.deleteAll();
        bodyApi().perform(get(bodyPath(pipeline.getId(), task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.code").value(OrchestrationErrorCode.TEST_CONNECTION_RESULT_NOT_FOUND.code()));
    }

    private void assertBodyOwnership(Pipeline pipeline, Task task, String suffix) throws Exception {
        Pipeline other = creator.createCustom("other-owner", List.of(request()));
        bodyApi().perform(get(bodyPath(other.getId(), task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.code").value(OrchestrationErrorCode.TASK_NOT_FOUND.code()));
        bodyApi().perform(get(bodyPath(pipeline.getId() + 1000, task.getId(), suffix)))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(jsonPath("$.code").value(OrchestrationErrorCode.PIPELINE_NOT_FOUND.code()));
    }

    private MockMvc bodyApi() {
        return MockMvcBuilders.standaloneSetup(new PipelineController(queries, control))
                .setControllerAdvice(new GlobalAdvice(clock)).build();
    }

    private String bodyPath(long pipelineId, long taskId, String suffix) {
        return "/api/v1/pipelines/" + pipelineId + "/tasks/" + taskId + suffix;
    }

    private StepOutcome runPoll(Pipeline pipeline) {
        Task task = task(pipeline);
        return runner.runStep(pipeline.getTarget(), task, attempts.findByTaskIdAndAttemptNumber(task.getId(), 1).orElseThrow());
    }

    private Pipeline create() {
        return creator.createCustom("connection", List.of(request()));
    }

    private Pipeline start() {
        Pipeline pipeline = create();
        clock.advance(Duration.ofSeconds(10));
        worker.pollOnce();
        return pipeline;
    }

    private CustomTaskRequest request() {
        return new CustomTaskRequest("TEST_CONNECTION_V1", null);
    }

    private void poll() {
        clock.advance(Duration.ofSeconds(4));
        worker.pollOnce();
    }

    private void succeedNextPoll() {
        client.poll = (request, version) -> new TestConnectionPollResponse(version,
            TestConnectionStatus.SUCCESS, FakeTestConnectionClient.response(200, " SUCCESS raw "));
    }

    private List<Task> chain(Pipeline pipeline) {
        return tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
    }

    private Task task(Pipeline pipeline) {
        return chain(pipeline).getFirst();
    }

    private TaskExternalExecution execution(Task task) {
        return executions.findByTaskId(task.getId()).orElseThrow();
    }

    private TestConnectionResult result(Task task) {
        return results.findByExternalExecutionId(execution(task).getId()).orElseThrow();
    }

    private PipelineStatus status(Pipeline pipeline) {
        return pipelines.findById(pipeline.getId()).orElseThrow().getStatus();
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeInfraManagerClient infraManagerClient() {
            return new FakeInfraManagerClient();
        }

        @Bean
        FakeTestConnectionClient installationClient() {
            return new FakeTestConnectionClient();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TestConnectionSettings testConnectionSettings() {
            return new TestConnectionSettings(Duration.ofMinutes(50), Duration.ofSeconds(4), 24);
        }

        @Bean
        InstallationSettings installationSettings() {
            return new InstallationSettings(Set.of(TaskOperation.TEST_CONNECTION,
                TaskOperation.DELETE_CONFIRMED_RESOURCES, TaskOperation.CONFIRM_RESOURCES_FROM_RECOMMENDATION),
                1048576, 1048576, Duration.ofSeconds(5), 24);
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder().executionTimeout(Duration.ofMinutes(50))
                .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).maxTerraformPollCallErrors(3).startDelay(Duration.ofSeconds(10)).build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder().workerPerPod(2).leaseDuration(Duration.ofSeconds(30))
                .apiCallTimeout(Duration.ofSeconds(15)).runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1)).backoffBase(Duration.ofMillis(100))
                .backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2).schedulerInitialDelay(Duration.ofSeconds(5)).build();
        }
    }
}
