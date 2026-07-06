package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.pipeline.TaskAttemptView;
import com.bff.pipeline.dto.pipeline.TerraformResultDetail;
import com.bff.pipeline.dto.pipeline.TerraformResultSummary;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.TaskNotFoundException;
import com.bff.pipeline.exception.TerraformResultNotFoundException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
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
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * terraform result 노출 경로를 검증한다(설계 §4.5). P5 확장 — task 상세의 attempt 항목에 job별 result
 * 메타(본문 없음, hasBody 존재 표시)가 인라인되는지 — 와 P11 본문 전용 lazy 조회 — 유니크 키 (task, attempt,
 * job) 행 하나의 본문/포인터 상태와 소유권 검증·404 계약 — 를 함께 본다. 실행은 실제 claim-pull wiring으로
 * 구동하고 관측은 Admin read path({@link PipelineQueryService})로만 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class,
        ConditionCheckTask.class, ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class,
        PipelineInserter.class, RecipeCatalog.class, PipelineQueryService.class, PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TerraformResultQueryTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineQueryService queryService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private TaskCheckRepository taskCheckRepository;
    @Autowired private TerraformResultRepository terraformResultRepository;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManagerClient;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManagerClient.onDispatch(() -> "[\"job-1\"]");
        infraManagerClient.onPoll(TerraformPoll::running);
        infraManagerClient.onCheck(() -> false);
        infraManagerClient.onResult(() -> "terraform: ok");
    }

    @AfterEach
    void clean() {
        taskCheckRepository.deleteAll();
        terraformResultRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void taskDetailInlinesPerJobResultMetadataAndTheBodyEndpointServesTheLog() {
        infraManagerClient.onResult(() -> "terraform: apply failed");
        Pipeline pipeline = createWithOneFailedOfTwoJobs("trq-meta");
        long taskId = firstTask(pipeline).getId();

        TaskAttemptView attempt = queryService.taskDetail(pipeline.getId(), taskId).attempts().getFirst();

        // attempt의 실패 원인 텍스트가 실패 job을 지목한다(errorCode만으로는 모르는 "왜")
        assertThat(attempt.errorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(attempt.failureDetail()).isEqualTo("jobs reported FAILED: [job-2]");
        // 메타만 인라인 — job별 성공 여부와 본문 존재 표시가 실리고, 본문 자체는 실리지 않는다
        assertThat(attempt.terraformResults())
                .extracting(TerraformResultSummary::jobId, TerraformResultSummary::succeeded,
                        TerraformResultSummary::hasBody)
                .containsExactly(tuple("job-1", true, true), tuple("job-2", false, true));

        // 본문 전용 엔드포인트(P11)가 저장된 log 본문을 lazy로 내준다
        TerraformResultDetail body = queryService.terraformResult(pipeline.getId(), taskId, 1, "job-2");
        assertThat(body.succeeded()).isFalse();
        assertThat(body.truncated()).isFalse();
        assertThat(body.resultPath()).isEqualTo("s3://bucket/job-2.log");
        assertThat(body.content()).isEqualTo("terraform: apply failed");
    }

    @Test
    void aResultBodyFetchFailureLeavesAPointerRowServedWithNullContent() {
        infraManagerClient.onResult(() -> { throw new CallFailedException("result storage down"); });
        Pipeline pipeline = createWithOneFailedOfTwoJobs("trq-pointer");
        long taskId = firstTask(pipeline).getId();

        // 조회 실패 job도 행은 남는다 — hasBody = false인 포인터 행(원본 추적은 resultPath)
        TaskAttemptView attempt = queryService.taskDetail(pipeline.getId(), taskId).attempts().getFirst();
        assertThat(attempt.terraformResults())
                .extracting(TerraformResultSummary::jobId, TerraformResultSummary::hasBody)
                .containsExactly(tuple("job-1", false), tuple("job-2", false));

        // 본문 엔드포인트는 행 부재(404)가 아니라 content = null인 200으로 답한다
        TerraformResultDetail body = queryService.terraformResult(pipeline.getId(), taskId, 1, "job-2");
        assertThat(body.content()).isNull();
        assertThat(body.resultPath()).isEqualTo("s3://bucket/job-2.log");
    }

    @Test
    void theBodyEndpointValidatesTheOwnershipChainAndMissingRowsAre404() {
        Pipeline pipeline = createWithOneFailedOfTwoJobs("trq-404");
        long taskId = firstTask(pipeline).getId();

        assertThatThrownBy(() -> queryService.terraformResult(pipeline.getId(), taskId, 1, "no-such-job"))
                .isInstanceOf(TerraformResultNotFoundException.class);
        assertThatThrownBy(() -> queryService.terraformResult(pipeline.getId() + 999, taskId, 1, "job-1"))
                .isInstanceOf(PipelineNotFoundException.class);

        Pipeline other = creator.create("trq-404-other", PipelineType.DELETE);
        assertThatThrownBy(() -> queryService.terraformResult(other.getId(), taskId, 1, "job-1"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void anOverlongFailureDetailIsClampedToTheColumnLength() {
        Pipeline pipeline = creator.create("trq-clamp", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> { throw new CallFailedException("x".repeat(600)); });

        pipelineWorker.pollOnce();

        String recorded = taskAttemptRepository.findByTaskIdAndAttemptNumber(firstTask(pipeline).getId(), 1)
                .orElseThrow().getFailureDetail();
        assertThat(recorded).hasSize(512).matches("x+");
    }

    /** 2-job dispatch에서 job-2만 실패로 종결시켜 attempt 1이 JOB_FAILED + result 2행으로 끝난 pipeline을 만든다. */
    private Pipeline createWithOneFailedOfTwoJobs(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\"]");
        infraManagerClient.onPollByJob(jobId -> "job-2".equals(jobId)
                ? TerraformPoll.failure("s3://bucket/job-2.log")
                : TerraformPoll.success());
        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS (attempt 1)
        pipelineWorker.pollOnce();   // poll → 전원 terminal, job-2 실패 → JOB_FAILED + result 기록
        return pipeline;
    }

    private Task firstTask(Pipeline pipeline) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst();
    }
}
