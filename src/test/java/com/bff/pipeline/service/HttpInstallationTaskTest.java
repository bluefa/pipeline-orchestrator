package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.client.FakeInstallationOperationsClient;
import com.bff.pipeline.client.InstallationOperationsClient.Recommendation;
import com.bff.pipeline.client.InstallationOperationsClient.VerifiedContext;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.InstallationSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.config.TestConnectionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.pipeline.CustomTaskRequest;
import com.bff.pipeline.dto.pipeline.TaskCatalogResponse;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskConfirmationInput;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.exception.InstallationRequestException;
import com.bff.pipeline.exception.OrchestrationErrorCode;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskConfirmationInputRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.InstallationOperationAvailability;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
import com.bff.pipeline.service.task.ConditionCheckTask;
import com.bff.pipeline.service.task.HttpRequestTask;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskConfirmationInputs;
import com.bff.pipeline.service.task.TaskStateMachine;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.TestConnectionExecutionService;
import com.bff.pipeline.service.task.TestConnectionResultRecorder;
import com.bff.pipeline.service.task.TestConnectionTask;
import com.bff.pipeline.service.task.terraform.TerraformJobStateRecorder;
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import com.bff.pipeline.utils.HttpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 공통 입력/삭제의 독립 실행, 원문/입력 보존과 정상 점유자만 채택하는 원자성을 실제 트랜잭션으로 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class, TaskStateMachine.class,
        TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class,
        ConditionCheckTask.class, HttpRequestTask.class, TaskConfirmationInputs.class, ObservationRecorder.class,
        TaskCanceller.class, PipelineCreator.class, PipelineInserter.class, PipelineControl.class, RecipeCatalog.class,
        InstallationOperationAvailability.class, PipelineQueryService.class, TestConnectionTask.class,
        TestConnectionExecutionService.class, TestConnectionResultRecorder.class, HttpInstallationTaskTest.Wiring.class})
class HttpInstallationTaskTest {
    static final Instant START = Instant.parse("2026-09-08T00:00:00Z");
    @Autowired PipelineCreator creator;
    @Autowired PipelineWorker worker;
    @Autowired PipelineClaimer claimer;
    @Autowired StepRunner runner;
    @Autowired StepReporter reporter;
    @Autowired PipelineControl control;
    @Autowired PipelineQueryService queries;
    @Autowired InstallationOperationAvailability availability;
    @Autowired FakeInstallationOperationsClient client;
    @Autowired FakeInfraManagerClient infraManager;
    @Autowired MutableClock clock;
    @Autowired PipelineRepository pipelines;
    @Autowired TaskRepository tasks;
    @Autowired TaskAttemptRepository attempts;
    @Autowired TaskCheckRepository checks;
    @Autowired TaskConfirmationInputRepository inputs;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StatementCapture statementCapture;
    @Autowired EntityManager entityManager;
    @Autowired TerraformResultRepository terraformResults;
    @Autowired TerraformJobStateRepository terraformJobStates;

    @BeforeEach
    void reset() {
        clock.set(START);
        client.reset();
        infraManager.onCloudProvider(CloudProvider.AWS);
        infraManager.onPoll(() -> TerraformPoll.running("RUNNING"));
        infraManager.onCheck(() -> false);
    }

    @AfterEach
    void clean() {
        terraformResults.deleteAll();
        terraformJobStates.deleteAll();
        checks.deleteAll();
        attempts.deleteAll();
        inputs.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void standaloneInputUsesTwoCallsWithinOneTaskForEveryProvider(CloudProvider provider) {
        infraManager.onCloudProvider(provider);
        Pipeline pipeline = inputPipeline();
        assertThat(chain(pipeline)).hasSize(1);
        worker.pollOnce();
        Task task = task(pipeline);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.RUNNING);
        assertThat(client.confirmations).isEmpty();
        assertThat(input(task).getRecommendationBody()).isEqualTo(FakeInstallationOperationsClient.BODY);
        worker.pollOnce();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.recommendations).hasSize(1);
        assertThat(client.confirmations).singleElement().satisfies(request -> {
            assertThat(request.body()).isEqualTo(FakeInstallationOperationsClient.BODY);
            assertThat(request.request().provider()).isEqualTo(provider);
        });
        assertThat(pipelines.findById(pipeline.getId()).orElseThrow().getActiveTarget()).isNull();
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task.getId())).hasSize(1);
        assertThat(queries.httpResponse(pipeline.getId(), task.getId(), 1).body()).isEmpty();
        assertThat(queries.confirmationInput(pipeline.getId(), task.getId()).recommendationBody())
                .isEqualTo(FakeInstallationOperationsClient.BODY);
    }

    @Test
    void repeatedDefinitionsOwnDifferentInputsAndRequestKeys() {
        Pipeline pipeline = creator.createCustom("repeated", List.of(inputRequest(), inputRequest()));
        Task first = chain(pipeline).getFirst();
        Task second = chain(pipeline).getLast();
        assertThat(input(first).getRequestKey()).isNotEqualTo(input(second).getRequestKey());
        worker.pollOnce();
        assertThat(tasks.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.BLOCKED);
        worker.pollOnce();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(200, "{\"second\":true}"),
                new VerifiedContext("approval-2", "generation-2"));
        worker.pollOnce();
        worker.pollOnce();
        assertThat(client.confirmations).hasSize(2);
        assertThat(input(first).getRecommendationBody()).isEqualTo(FakeInstallationOperationsClient.BODY);
        assertThat(input(second).getRecommendationBody()).isEqualTo("{\"second\":true}");
    }

    @Test
    void postRetryKeepsTheSnapshotAndDoesNotFetchAgain() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        client.confirmation = request -> FakeInstallationOperationsClient.response(503, " {\"failure\": true} ");
        worker.pollOnce();
        Task task = task(pipeline);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task.getFailCount()).isEqualTo(1);
        assertThat(attempt(task, 1).getHttpResponse()).isEqualTo(" {\"failure\": true} ");
        client.confirmation = request -> FakeInstallationOperationsClient.response(201, "");
        clock.advance(Duration.ofSeconds(5));
        worker.pollOnce();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.recommendations).hasSize(1);
        assertThat(client.confirmations).extracting(request -> request.request().requestKey()).containsOnly(input(task).getRequestKey());
        assertThat(client.confirmations).extracting(request -> request.body()).containsOnly(FakeInstallationOperationsClient.BODY);
    }

    @Test
    void staleRecommendationCannotCaptureInputOrOpenAnAttempt() {
        Pipeline pipeline = inputPipeline();
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), task(pipeline), null);
        reporter.writeBack(pipeline.getId(), "stale-token", outcome);
        assertThat(input(task(pipeline)).getRecommendationBody()).isNull();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task(pipeline).getId())).isEmpty();
        reporter.writeBack(pipeline.getId(), claim.token(), outcome);
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void cancellationDiscardsRecommendationAndDoesNotReviveThePipeline() {
        Pipeline pipeline = inputPipeline();
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), task(pipeline), null);
        control.cancel(pipeline.getId());
        reporter.writeBack(pipeline.getId(), claim.token(), outcome);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(input(task(pipeline)).getRecommendationBody()).isNull();
        assertThat(client.confirmations).isEmpty();
    }

    @Test
    void failedCommitRollsBackInputAttemptAndTaskProgressTogether() {
        Pipeline pipeline = inputPipeline();
        Claim claim = claimer.claimOneDue().orElseThrow();
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), task(pipeline), null);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            reporter.writeBack(pipeline.getId(), claim.token(), outcome);
            throw new IllegalStateException("simulated commit failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(input(task(pipeline)).getRecommendationBody()).isNull();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task(pipeline).getId())).isEmpty();
        reporter.writeBack(pipeline.getId(), claim.token(), outcome);
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void missingInputFailsWithoutPosting() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        TaskAttempt recommendation = attempt(task(pipeline), 1);
        inputs.delete(input(task(pipeline)));
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_MISSING);
        assertThat(client.confirmations).isEmpty();
        assertRecommendationEvidencePreserved(recommendation, task(pipeline));
    }

    @Test
    void corruptDigestFailsWithoutPosting() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        TaskConfirmationInput input = input(task(pipeline));
        TaskAttempt recommendation = attempt(task(pipeline), 1);
        input.setBodyDigest("incorrect");
        inputs.save(input);
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(client.confirmations).isEmpty();
        assertRecommendationEvidencePreserved(recommendation, task(pipeline));
    }

    @Test
    void missingAttemptIsRecoveredFromAnIntactInputWithoutAnotherGet() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        attempts.deleteAll();
        worker.pollOnce();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.recommendations).hasSize(1);
        assertThat(attempt(task(pipeline), 1).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(attempt(task(pipeline), 1).getConfirmationInputId()).isEqualTo(input(task(pipeline)).getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {
        401, 403, 202, 400
    })
    void nonRetryableHttpContractsCloseTheAttempt(int status) {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        client.confirmation = request -> FakeInstallationOperationsClient.response(status, "unmodified failure");
        worker.pollOnce();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline).getFailCount()).isZero();
        assertThat(attempt(task(pipeline), 1).getHttpResponse()).isEqualTo("unmodified failure");
    }

    @ParameterizedTest
    @ValueSource(ints = {
        409, 412
    })
    void mutationConflictIsBusinessFailure(int status) {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        client.confirmation = request -> FakeInstallationOperationsClient.response(status, "conflict");
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.CONFIRMATION_CONFLICT);
    }

    @Test
    void largeSuccessIsBoundedAndNeverSubmittedAsInput() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(200, "가".repeat(400000)),
                new VerifiedContext("approval", "generation"));
        worker.pollOnce();
        TaskAttempt attempt = attempt(task(pipeline), 1);
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.RESPONSE_TOO_LARGE);
        assertThat(attempt.getResponseTruncated()).isTrue();
        assertThat(attempt.getHttpResponse().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1048576);
        assertThat(attempt.getHttpResponse()).doesNotContain("�");
        assertThat(input(task(pipeline)).getRecommendationBody()).isNull();
    }

    @Test
    void largeTransientErrorRemainsRetryableAndPreservesBoundedRawResponse() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> {
            throw new CallFailedException("external error", true,
                FakeInstallationOperationsClient.response(503, "가".repeat(400000)));
        };
        worker.pollOnce();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(attempt(task(pipeline), 1).getResponseTruncated()).isTrue();
        assertThat(attempt(task(pipeline), 1).getHttpStatusCode()).isEqualTo(503);
    }

    @Test
    void oversizedIdentityIsRejectedRatherThanTruncated() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(200, "{}"),
                new VerifiedContext("x".repeat(TaskConfirmationInput.CONTEXT_VALUE_LENGTH + 1), "generation"));
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(input(task(pipeline)).getRecommendationBody()).isNull();
    }

    @Test
    void standaloneDeleteUsesStableTaskIdentityAndDoesNotRequireRecommendation() {
        Pipeline pipeline = creator.createCustom("delete", List.of(new CustomTaskRequest("DELETE_CONFIRMED_RESOURCES_V1", null)));
        worker.pollOnce();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.deletions).singleElement().satisfies(request ->
                assertThat(request.requestKey()).isEqualTo("confirmation-delete:v1:task:" + task(pipeline).getId()));
        assertThat(inputs.findAll()).isEmpty();
        assertThat(client.recommendations).isEmpty();
    }

    @Test
    void capabilityGateIsTheSameForCustomAndCatalog() {
        client.available = false;
        assertThatThrownBy(this::inputPipeline).isInstanceOf(InstallationRequestException.class);
        assertThatThrownBy(() -> creator.create("catalog", PipelineType.RECONFIRM))
                .isInstanceOfSatisfying(InstallationRequestException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrchestrationErrorCode.OPERATION_UNAVAILABLE.code()));
        assertThat(TaskCatalogResponse.of(CloudProvider.AWS, definition -> availability.isAvailable(CloudProvider.AWS, definition))
                .taskDefinitions()).filteredOn(entry -> entry.name().equals(inputRequest().name()))
                .singleElement().satisfies(entry -> {
            assertThat(entry.customAllowed()).isTrue();
            assertThat(entry.executionAvailable()).isFalse();
                });
    }

    @Test
    void losingRuntimeCapabilityFailsWithoutCallingTheServer() {
        Pipeline pipeline = inputPipeline();
        client.available = false;
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.OPERATION_UNAVAILABLE);
        assertThat(client.recommendations).isEmpty();
    }

    @Test
    void capabilityLossAfterRecommendationPreservesTheLastCallEvidence() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        TaskAttempt recommendation = attempt(task(pipeline), 1);
        client.available = false;
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.OPERATION_UNAVAILABLE);
        assertThat(client.recommendations).hasSize(1);
        assertThat(client.confirmations).isEmpty();
        assertRecommendationEvidencePreserved(recommendation, task(pipeline));
    }

    @Test
    void postTimeoutReplacesGetEvidenceWithAnUnansweredPost() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        client.confirmation = request -> {
            throw new CallTimeoutException();
        };
        worker.pollOnce();
        Task current = task(pipeline);
        TaskAttempt failed = attempt(current, 1);
        assertThat(current.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.CALL_TIMEOUT);
        assertThat(failed.getHttpOperation()).isEqualTo(HttpResponses.CONFIRMATION_POST);
        assertThat(failed.getHttpResponse()).isNull();
        assertThat(failed.getHttpStatusCode()).isNull();
        assertThat(failed.getResponseReceivedAt()).isNull();
        assertThat(failed.getConfirmationInputId()).isEqualTo(input(current).getId());
        assertThat(client.recommendations).hasSize(1);
        assertThat(client.confirmations).hasSize(1);
    }

    @Test
    void taskDetailsOmitRawBodyButDedicatedEndpointsPreserveIt() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        worker.pollOnce();
        Task task = task(pipeline);
        statementCapture.statements.clear();
        assertThat(queries.taskDetail(pipeline.getId(), task.getId()).attempts()).singleElement()
                .satisfies(attempt -> {
            assertThat(attempt.response()).isNull();
            assertThat(attempt.http().statusCode()).isEqualTo(201);
                });
        assertThat(statementCapture.statements).filteredOn(statement -> statement.contains("from task_attempt"))
                .isNotEmpty().allSatisfy(statement -> assertThat(statement).doesNotContain("http_response"));
        assertThat(queries.confirmationInput(pipeline.getId(), task.getId()).bodyDigest())
                .isEqualTo(HttpResponses.digest(FakeInstallationOperationsClient.BODY));
        assertThatThrownBy(() -> queries.confirmationInput(pipeline.getId() + 999, task.getId())).hasMessageContaining("pipeline");
    }

    @Test
    void encodingValidationUsesOriginalContentTypeBeforeMetadataClamping() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(200, "{}")
                .toBuilder().contentType("application/json; padding=" + "x".repeat(150) + ";charset=ISO-8859-1").build(),
                new VerifiedContext("approval", "generation"));
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(attempt(task(pipeline), 1).getResponseContentType()).hasSize(TaskAttempt.CONTENT_TYPE_LENGTH);
        assertThat(attempt(task(pipeline), 1).getHttpResponse()).isEqualTo("{}");
    }

    @Test
    void customInputUsesAwsOptionWithoutAdditionalApprovalRequestFields() {
        Pipeline pipeline = creator.createCustom("option", List.of(new CustomTaskRequest(inputRequest().name(), null, true)));
        worker.pollOnce();
        worker.pollOnce();
        assertThat(client.confirmations).singleElement().satisfies(request -> assertThat(request.applyNlbSecurityGroup()).isTrue());
    }

    @ParameterizedTest
    @EnumSource(value = CloudProvider.class, names = {
        "GCP", "AZURE", "IDC"
    })
    void awsOnlyOptionIsRejectedForOtherProviders(CloudProvider provider) {
        infraManager.onCloudProvider(provider);
        assertThatThrownBy(() -> creator.createCustom("option", List.of(new CustomTaskRequest(inputRequest().name(), null, true))))
                .isInstanceOf(InstallationRequestException.class);
    }

    @Test
    void inputOptionIsRejectedOnDeleteTask() {
        assertThatThrownBy(() -> creator.createCustom("option", List.of(new CustomTaskRequest("DELETE_CONFIRMED_RESOURCES_V1", null, true))))
                .isInstanceOf(InstallationRequestException.class);
    }

    @Test
    void httpRetryBudgetTerminatesAtTwentyFourAttempts() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(503, "busy"), null);
        for (int attempt = 0; attempt < 24; attempt++) {
            worker.pollOnce();
            clock.advance(Duration.ofSeconds(5));
        }
        assertThat(task(pipeline).getFailCount()).isEqualTo(24);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
        assertThat(client.recommendations).hasSize(24);
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task(pipeline).getId())).hasSize(24);
    }

    @Test
    void unfilteredAvailabilityRequiresEveryApplicableProvider() {
        client.unsupportedProviders.add(CloudProvider.GCP);
        assertThat(availability.isAvailable(CloudProvider.AWS, TaskDefinition.CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1)).isTrue();
        assertThat(availability.isAvailable(CloudProvider.GCP, TaskDefinition.CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1)).isFalse();
        assertThat(availability.isAvailable(null, TaskDefinition.CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1)).isFalse();
    }

    @Test
    void reconfirmationRequiresItsDeletionOrderContractBeyondCommonOperations() {
        client.reconfirmationAvailable = false;
        assertThatThrownBy(() -> creator.preview("target", PipelineType.RECONFIRM))
                .isInstanceOfSatisfying(InstallationRequestException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrchestrationErrorCode.OPERATION_UNAVAILABLE.code()));
        assertThat(inputPipeline().getType()).isEqualTo(PipelineType.CUSTOM);
    }

    @Test
    void recommendationAbsenceIsTerminalBusinessFailure() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(404, "missing"), null);
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_NOT_FOUND);
        assertThat(attempt(task(pipeline), 1).getHttpResponse()).isEqualTo("missing");
        assertThat(client.confirmations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "[]", "{} trailing", ""
    })
    void malformedRecommendationCannotBecomeAnInput(String body) {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> new Recommendation(FakeInstallationOperationsClient.response(200, body),
                new VerifiedContext("approval", "generation"));
        worker.pollOnce();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(input(task(pipeline)).getRecommendationBody()).isNull();
        assertThat(client.confirmations).isEmpty();
    }

    @Test
    void recommendationTimeoutKeepsOnlyTheOperationMetadataAndRetries() {
        Pipeline pipeline = inputPipeline();
        client.recommendation = request -> {
            throw new CallTimeoutException();
        };
        worker.pollOnce();
        Task current = task(pipeline);
        TaskAttempt failed = attempt(current, 1);
        assertThat(current.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(current.getFailCount()).isEqualTo(1);
        assertThat(failed.getErrorCode()).isEqualTo(ErrorCode.CALL_TIMEOUT);
        assertThat(failed.getHttpOperation()).isEqualTo(HttpResponses.RECOMMENDATION_GET);
        assertThat(failed.getHttpStatusCode()).isNull();
        assertThat(failed.getHttpResponse()).isNull();
        assertThat(current.getNextCheckAt()).isEqualTo(clock.instant().plusSeconds(5));
    }

    @ParameterizedTest
    @EnumSource(CloudProvider.class)
    void catalogReconfirmationCompletesTheFullChainAndUsesOnlyOneInput(CloudProvider provider) {
        infraManager.onCloudProvider(provider);
        infraManager.onPoll(() -> TerraformPoll.success("SUCCEEDED"));
        Pipeline pipeline = creator.create("reconfirmation", PipelineType.RECONFIRM, provider == CloudProvider.AWS);
        List<Task> chain = chain(pipeline);
        assertThat(chain).extracting(Task::getTaskDefinition).containsExactlyElementsOf(reconfirmationSteps(provider));
        Task inputTask = chain.getLast();
        assertThat(inputs.findAll()).singleElement().satisfies(input -> assertThat(input.getTaskId()).isEqualTo(inputTask.getId()));
        client.deletion = request -> {
            assertThat(chain(pipeline)).filteredOn(Task::getConsumesTerraformSlot)
                    .allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE));
            return FakeInstallationOperationsClient.response(200, "deleted");
        };
        for (int turn = 0; turn < 20 && !status(pipeline).isTerminal(); turn++) worker.pollOnce();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.deletions).hasSize(1);
        assertThat(client.recommendations).hasSize(1);
        assertThat(client.confirmations).singleElement().satisfies(request ->
                assertThat(request.applyNlbSecurityGroup()).isEqualTo(provider == CloudProvider.AWS));
    }

    @ParameterizedTest
    @EnumSource(value = CloudProvider.class, names = {
        "GCP", "AZURE", "IDC"
    })
    void catalogRejectsAwsOnlyOptionForOtherProviders(CloudProvider provider) {
        infraManager.onCloudProvider(provider);
        assertThatThrownBy(() -> creator.create("invalid-option", PipelineType.RECONFIRM, true))
                .isInstanceOf(InstallationRequestException.class);
        assertThat(pipelines.findAll()).isEmpty();
    }

    @Test
    void activeInstallBlocksReconfirmationAndCustomInputForTheSameTarget() {
        creator.create("same-target", PipelineType.INSTALL);
        assertThatThrownBy(() -> creator.create("same-target", PipelineType.RECONFIRM))
                .isInstanceOf(PipelineAlreadyActiveException.class);
        assertThatThrownBy(() -> creator.createCustom("same-target", List.of(inputRequest())))
                .isInstanceOf(PipelineAlreadyActiveException.class);
        assertThat(pipelines.findAll()).hasSize(1);
        assertThat(inputs.findAll()).isEmpty();
    }

    @Test
    void stalePostCompletionDoesNotOverwriteTheAdoptedGetResponse() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        Task current = task(pipeline);
        Claim claim = claimer.claimOneDue().orElseThrow();
        client.confirmation = request -> FakeInstallationOperationsClient.response(201, "post completed");
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), current, attempt(current, 1));
        reporter.writeBack(pipeline.getId(), "stale-post-token", outcome);
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(attempt(current, 1).getHttpOperation()).isEqualTo(HttpResponses.RECOMMENDATION_GET);
        assertThat(attempt(current, 1).getHttpResponse()).isEqualTo(FakeInstallationOperationsClient.BODY);
        assertThat(client.confirmations).hasSize(1);
        reporter.writeBack(pipeline.getId(), claim.token(), outcome);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void cancellationDiscardsPostCompletionAndPreservesTheInputSnapshot() {
        Pipeline pipeline = inputPipeline();
        worker.pollOnce();
        Task current = task(pipeline);
        Claim claim = claimer.claimOneDue().orElseThrow();
        client.confirmation = request -> FakeInstallationOperationsClient.response(201, "post completed");
        StepOutcome outcome = runner.runStep(pipeline.getTarget(), current, attempt(current, 1));
        control.cancel(pipeline.getId());
        reporter.writeBack(pipeline.getId(), claim.token(), outcome);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(attempt(current, 1).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(attempt(current, 1).getHttpResponse()).isEqualTo(FakeInstallationOperationsClient.BODY);
        assertThat(input(current).getRecommendationBody()).isEqualTo(FakeInstallationOperationsClient.BODY);
        assertThat(client.confirmations).hasSize(1);
    }

    @Test
    void transientDeleteFailureRetriesWithTheSameVersionedKey() {
        Pipeline pipeline = creator.createCustom("retry-delete", List.of(new CustomTaskRequest("DELETE_CONFIRMED_RESOURCES_V1", null)));
        client.deletion = request -> FakeInstallationOperationsClient.response(503, "retryable delete");
        worker.pollOnce();
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(attempt(task(pipeline), 1).getHttpResponse()).isEqualTo("retryable delete");
        client.deletion = request -> FakeInstallationOperationsClient.response(200, "deleted");
        clock.advance(Duration.ofSeconds(5));
        worker.pollOnce();
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
        assertThat(client.deletions).hasSize(2).extracting(request -> request.requestKey())
                .containsOnly("confirmation-delete:v1:task:" + task(pipeline).getId());
        assertThat(attempts.findByTaskIdOrderByAttemptNumberAsc(task(pipeline).getId())).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DELETE_CONFIRMED_RESOURCES_V1", "CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1"
    })
    void missingStoredProviderFailsWithoutAnExternalCall(String definition) {
        Pipeline pipeline = creator.createCustom("missing-provider", List.of(new CustomTaskRequest(definition, null)));
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> entityManager
                .createQuery("update Pipeline p set p.cloudProvider = null where p.id = :id")
                .setParameter("id", pipeline.getId()).executeUpdate());
        worker.pollOnce();
        assertThat(task(pipeline).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_INPUT_INVALID);
        assertThat(task(pipeline).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(client.recommendations).isEmpty();
        assertThat(client.deletions).isEmpty();
        assertThat(client.confirmations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "AWS_SERVICE_DESTROY_V1", "NETWORK_READY_V1"
    })
    void legacyAttemptsHaveNoHttpMetadataObject(String definition) {
        Pipeline pipeline = creator.createCustom("legacy", List.of(new CustomTaskRequest(definition, null)));
        worker.pollOnce();
        assertThat(queries.taskDetail(pipeline.getId(), task(pipeline).getId()).attempts())
                .singleElement().satisfies(attempt -> assertThat(attempt.http()).isNull());
    }

    @Test
    void inputRequestKeyUsesTheVersionedContractAcrossBothCalls() {
        Pipeline pipeline = inputPipeline();
        Task current = task(pipeline);
        String expectedKey = "confirmation-input:v1:task:" + current.getId();
        assertThat(input(current).getRequestKey()).isEqualTo(expectedKey);
        worker.pollOnce();
        worker.pollOnce();
        assertThat(client.recommendations).singleElement().satisfies(request -> assertThat(request.requestKey()).isEqualTo(expectedKey));
        assertThat(client.confirmations).singleElement().satisfies(request -> assertThat(request.request().requestKey()).isEqualTo(expectedKey));
    }

    private void assertRecommendationEvidencePreserved(TaskAttempt previous, Task current) {
        TaskAttempt terminal = attempt(current, 1);
        assertThat(terminal.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(terminal.getHttpOperation()).isEqualTo(HttpResponses.RECOMMENDATION_GET);
        assertThat(terminal.getHttpResponse()).isEqualTo(previous.getHttpResponse());
        assertThat(terminal.getHttpStatusCode()).isEqualTo(previous.getHttpStatusCode());
        assertThat(terminal.getResponseContentType()).isEqualTo(previous.getResponseContentType());
        assertThat(terminal.getResponseReceivedAt()).isEqualTo(previous.getResponseReceivedAt());
        assertThat(terminal.getResponseTruncated()).isEqualTo(previous.getResponseTruncated());
        assertThat(terminal.getConfirmationInputId()).isEqualTo(previous.getConfirmationInputId());
    }

    private List<String> reconfirmationSteps(CloudProvider provider) {
        return switch (provider) {
            case AWS -> List.of("AWS_BDC_SERVICE_LEVEL_DESTROY_V1", "AWS_BDC_COMMON_DESTROY_V1", "AWS_SERVICE_DESTROY_V1",
                    "DELETE_CONFIRMED_RESOURCES_V1", "CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1");
            case GCP -> List.of("GCP_BDC_DESTROY_V1", "GCP_SERVICE_DESTROY_V1", "DELETE_CONFIRMED_RESOURCES_V1",
                    "CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1");
            case AZURE -> List.of("AZURE_BDC_DESTROY_V1", "DELETE_CONFIRMED_RESOURCES_V1", "CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1");
            case IDC -> List.of("IDC_BDP_DESTROY_V1", "IDC_CX_DESTROY_V1", "DELETE_CONFIRMED_RESOURCES_V1",
                    "CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1");
        };
    }

    private Pipeline inputPipeline() {
        return creator.createCustom("input", List.of(inputRequest()));
    }

    private CustomTaskRequest inputRequest() {
        return new CustomTaskRequest("CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1", null);
    }

    private List<Task> chain(Pipeline pipeline) {
        return tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
    }

    private Task task(Pipeline pipeline) {
        return chain(pipeline).getFirst();
    }

    private TaskConfirmationInput input(Task task) {
        return inputs.findByTaskId(task.getId()).orElseThrow();
    }

    private TaskAttempt attempt(Task task, int number) {
        return attempts.findByTaskIdAndAttemptNumber(task.getId(), number).orElseThrow();
    }

    private PipelineStatus status(Pipeline pipeline) {
        return pipelines.findById(pipeline.getId()).orElseThrow().getStatus();
    }

    /** 전용 본문이 일반 상세 조회에서 DB로부터 읽히지 않는지 실제 SQL을 수집한다. */
    static class StatementCapture implements StatementInspector {
        final List<String> statements = new ArrayList<>();
        public String inspect(String statement) {
            statements.add(statement);
            return statement;
        }
    }

    /** 실제 트랜잭션과 고정 시각을 유지하면서 외부 경계만 fake로 대체한다. */
    @TestConfiguration
    static class Wiring {
        @Bean
        StatementCapture statementCapture() {
            return new StatementCapture();
        }

        @Bean
        HibernatePropertiesCustomizer captureStatements(StatementCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }

        @Bean
        TestConnectionSettings testConnectionSettings() {
            return new TestConnectionSettings(Duration.ofMinutes(50), Duration.ofSeconds(4), 24);
        }

        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeInfraManagerClient infraManagerClient() {
            return new FakeInfraManagerClient();
        }

        @Bean
        FakeInstallationOperationsClient installationClient() {
            return new FakeInstallationOperationsClient();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        InstallationSettings installationSettings() {
            return new InstallationSettings(Set.of(TaskOperation.DELETE_CONFIRMED_RESOURCES,
                    TaskOperation.CONFIRM_RESOURCES_FROM_RECOMMENDATION), 1048576, 1048576, Duration.ofSeconds(5), 24);
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder().executionTimeout(Duration.ofMinutes(50)).pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2).maxTerraformPollCallErrors(3).startDelay(Duration.ZERO).build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder().workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5)).build();
        }
    }
}
