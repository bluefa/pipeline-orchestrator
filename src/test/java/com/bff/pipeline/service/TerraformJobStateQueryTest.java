package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.pipeline.TaskAttemptView;
import com.bff.pipeline.dto.pipeline.TerraformJobStateDetail;
import com.bff.pipeline.dto.pipeline.TerraformJobStateSummary;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.TaskNotFoundException;
import com.bff.pipeline.exception.TerraformJobStateNotFoundException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
import com.bff.pipeline.service.task.ConditionCheckTask;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskStateMachine;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.terraform.TerraformJobStateRecorder;
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * terraform job 진행-시점 상태 관찰의 노출 경로를 검증한다. task 상세의 attempt에 job별 live 상태가 인라인되는지,
 * per-job 상태 엔드포인트가 유니크 키 (task, attempt, job) 행을 소유권 검증·404 계약과 함께 내주는지, 그리고
 * 핵심 가치인 "종결 전(동작 중) 조회 가능"을 함께 본다. 실행은 실제 claim-pull wiring으로 구동하고 관측은
 * Admin read path({@link PipelineQueryService})로만 한다({@code TerraformResultQueryTest}와 대칭).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class,
        TerraformJobStateRecorder.class, ConditionCheckTask.class, ObservationRecorder.class, TaskCanceller.class,
        PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class, PipelineQueryService.class,
        PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TerraformJobStateQueryTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineQueryService queryService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private TaskCheckRepository taskCheckRepository;
    @Autowired private TerraformJobStateRepository terraformJobStateRepository;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManagerClient;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        infraManagerClient.onPoll(() -> TerraformPoll.running("RUNNING"));
        infraManagerClient.onCheck(() -> false);
        infraManagerClient.onResult(() -> "terraform: ok");
    }

    @AfterEach
    void clean() {
        taskCheckRepository.deleteAll();
        terraformJobStateRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void taskDetailInlinesPerJobStateAndTheStateEndpointServesIt() {
        Pipeline pipeline = createWithOneFailedOfTwoJobs("tjs-meta");
        long taskId = firstTask(pipeline).getId();

        TaskAttemptView attempt = queryService.taskDetail(pipeline.getId(), taskId).attempts().getFirst();

        // job별 진행-시점 상태가 인라인된다 — 실패 job은 상태와 사유를, 성공 job은 상태를 싣는다
        assertThat(attempt.jobStates())
                .extracting(TerraformJobStateSummary::jobId, TerraformJobStateSummary::lastState,
                        TerraformJobStateSummary::lastFailReason)
                .containsExactly(tuple("job-1", "COMPLETED", null),
                        tuple("job-2", "FAILED", "Error: exit status 1"));

        // per-job 상태 엔드포인트가 유니크 키 행 하나를 내준다
        TerraformJobStateDetail state = queryService.terraformJobState(pipeline.getId(), taskId, 1, "job-2");
        assertThat(state.lastState()).isEqualTo("FAILED");
        assertThat(state.lastFailReason()).isEqualTo("Error: exit status 1");
        assertThat(state.lastError()).isNull();
    }

    @Test
    void liveStateIsQueryableWhileTheTaskIsStillInProgress() {
        Pipeline pipeline = creator.create("tjs-live", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        // 아직 진행 중(종결 아님) — 상태 필드로는 안 보이는 원문 body까지 함께 관측된다
        infraManagerClient.onPoll(() -> TerraformPoll.running("APPLYING")
                .withResponse("{\"terraformState\":\"APPLYING\",\"id\":7}"));

        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS
        pipelineWorker.pollOnce();   // poll → 아직 running, 종결 전
        long taskId = firstTask(pipeline).getId();

        // 종결 전인데도 job의 마지막 관측 상태와 응답 원문이 조회된다 — 이 관찰의 핵심 가치
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        TerraformJobStateDetail state = queryService.terraformJobState(pipeline.getId(), taskId, 1, "job-1");
        assertThat(state.lastState()).isEqualTo("APPLYING");
        assertThat(state.lastResponse()).isEqualTo("{\"terraformState\":\"APPLYING\",\"id\":7}");
        assertThat(state.lastError()).isNull();
    }

    @Test
    void theStateEndpointValidatesTheOwnershipChainAndMissingRowsAre404() {
        Pipeline pipeline = createWithOneFailedOfTwoJobs("tjs-404");
        long taskId = firstTask(pipeline).getId();

        assertThatThrownBy(() -> queryService.terraformJobState(pipeline.getId(), taskId, 1, "no-such-job"))
                .isInstanceOf(TerraformJobStateNotFoundException.class);
        assertThatThrownBy(() -> queryService.terraformJobState(pipeline.getId() + 999, taskId, 1, "job-1"))
                .isInstanceOf(PipelineNotFoundException.class);

        Pipeline other = creator.create("tjs-404-other", PipelineType.DELETE);
        assertThatThrownBy(() -> queryService.terraformJobState(other.getId(), taskId, 1, "job-1"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    /** 2-job dispatch에서 job-2만 실패(사유 포함)로 종결시켜 attempt 1이 job별 상태 2행으로 끝난 pipeline을 만든다. */
    private Pipeline createWithOneFailedOfTwoJobs(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\"]");
        infraManagerClient.onPollByJob(jobId -> "job-2".equals(jobId)
                ? TerraformPoll.failure("FAILED", "Error: exit status 1")
                : TerraformPoll.success("COMPLETED"));
        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS (attempt 1)
        pipelineWorker.pollOnce();   // poll → 전원 terminal, job-2 실패 → job별 상태 기록
        return pipeline;
    }

    private Task firstTask(Pipeline pipeline) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst();
    }
}
