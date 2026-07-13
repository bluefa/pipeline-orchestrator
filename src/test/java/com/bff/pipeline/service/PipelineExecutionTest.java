package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.entity.TerraformJobState;
import com.bff.pipeline.entity.TerraformResult;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.execution.PipelineWorker;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.task.ConditionCheckTask;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskStateMachine;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.terraform.TerraformJobStateRecorder;
import com.bff.pipeline.service.task.terraform.TerraformResultRecorder;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.bff.pipeline.exception.CallFailedException;

/**
 * ADR-021 claim-pull 실행 모델의 엔드-투-엔드 테스트이다. {@link PipelineWorker#pollOnce}가 due pipeline 하나를
 * claim(claim 트랜잭션) → 외부호출(run 단계) → write-back(write-back 트랜잭션)으로 한 단계씩 구동한다(스케줄러 스레드 없이 결정적으로). fake에 동작을
 * 스크립팅하고 {@link MutableClock}으로 due-ness/lease 만료를 제어한다. {@code NOT_SUPPORTED}가 테스트 래핑
 * 트랜잭션을 억제하므로 각 claim 트랜잭션과 write-back 트랜잭션이 프로덕션과 동일하게 독립 커밋한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
        TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class, ConditionCheckTask.class,
        ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class, PipelineInserter.class,
        PipelineControl.class, RecipeCatalog.class, PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineExecutionTest {

    static final Instant START = Instant.parse("2026-06-23T00:00:00Z");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final int MAX_TF_POLL_ERRORS = 10;   // Wiring의 PipelineSettings와 폴 임계 테스트가 공유하는 값

    @Autowired private PipelineWorker pipelineWorker;
    @Autowired private PipelineClaimer pipelineClaimer;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskAttemptRepository taskAttemptRepository;
    @Autowired private TerraformResultRepository terraformResultRepository;
    @Autowired private TerraformJobStateRepository terraformJobStateRepository;
    @Autowired private MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManagerClient;
    @Autowired private JdbcTemplate jdbcTemplate;

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
        terraformResultRepository.deleteAll();
        terraformJobStateRepository.deleteAll();
        taskAttemptRepository.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    // ── e2e domain through the two-transaction pipelineWorker ────────────────────────────────────────────

    @Test
    void terraformHappyPathReachesDoneAndRecordsTheResponse() {
        // AWS delete recipe = destroy 3단계(BDC service level → BDC common → 서비스), 단계마다 dispatch + poll.
        Pipeline pipeline = creator.create("e-happy", PipelineType.DELETE);
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));

        pipelineWorker.pollOnce();   // dispatch (BDC service level destroy) → IN_PROGRESS
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(attempt(pipeline, 0).getResponse()).isEqualTo("[\"job-1\"]");

        pipelineWorker.pollOnce();   // poll → DONE + promote 다음 destroy
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.READY);

        pipelineWorker.pollOnce();   // dispatch (BDC common destroy)
        pipelineWorker.pollOnce();   // poll → DONE
        pipelineWorker.pollOnce();   // dispatch (서비스 destroy)
        pipelineWorker.pollOnce();   // poll → DONE → pipeline DONE
        assertThat(task(pipeline, 2).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void recordsATerraformResultRowPerJobOnTheConcludingTurn() {
        Pipeline pipeline = creator.create("e-postcheck", PipelineType.DELETE);
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
        infraManagerClient.onResult(() -> "Destroy complete! Resources: 3 destroyed.");

        pipelineWorker.pollOnce();   // dispatch
        pipelineWorker.pollOnce();   // poll success → DONE + postCheck 관찰 기록(확장 A)

        assertThat(terraformResultRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getTaskId()).isEqualTo(task(pipeline, 0).getId());
            assertThat(row.getAttemptNumber()).isEqualTo(1);
            assertThat(row.getJobId()).isEqualTo("job-1");
            assertThat(row.isSucceeded()).isTrue();
            assertThat(row.getResult()).isEqualTo("Destroy complete! Resources: 3 destroyed.");
            assertThat(row.isTruncated()).isFalse();
        });
    }

    @Test
    void aFailedJobWaitsForItsSiblingsBeforeConcludingJobFailed() {
        // 집계 정책(전원 terminal 대기): FAILED job을 봤어도 형제 job이 인프라에서 손을 뗄 때까지 판정을 미룬다.
        Pipeline pipeline = creator.create("e-wait", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\"]");
        Map<String, TerraformPoll> polls = new HashMap<>();
        polls.put("job-1", TerraformPoll.failure("FAILED", null));
        polls.put("job-2", TerraformPoll.running("RUNNING"));
        infraManagerClient.onPollByJob(polls::get);

        pipelineWorker.pollOnce();   // dispatch (attempt 1)
        pipelineWorker.pollOnce();   // poll: job-1 FAILED, job-2 running → 아직 판정하지 않는다
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline, 0).getFailCount()).isZero();
        assertThat(terraformResultRepository.findAll()).isEmpty();   // 종결 turn 전에는 기록도 없다

        polls.put("job-2", TerraformPoll.success("COMPLETED"));
        clock.advance(Duration.ofMinutes(11));   // polling_interval(PT10M) 경과 후 다음 폴 — executionTimeout(PT50M) 이내
        pipelineWorker.pollOnce();   // 전원 terminal → JOB_FAILED(재시도 가능) + 두 job 모두 관찰 기록
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(terraformResultRepository.findAll())
                .extracting(TerraformResult::getJobId, TerraformResult::isSucceeded)
                .containsExactlyInAnyOrder(tuple("job-1", false), tuple("job-2", true));
    }

    @Test
    void aDeadlineWithAFailedJobConcludesJobFailedAndRecordsOnlyFinishedJobs() {
        Pipeline pipeline = creator.create("e-deadline", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\"]");
        Map<String, TerraformPoll> polls = new HashMap<>();
        polls.put("job-1", TerraformPoll.failure("FAILED", null));
        polls.put("job-2", TerraformPoll.running("RUNNING"));   // 데드라인까지 안 끝난다
        infraManagerClient.onPollByJob(polls::get);

        pipelineWorker.pollOnce();   // dispatch (attempt 1)
        clock.advance(Duration.ofHours(1));   // executionTimeout(PT50M) 초과
        pipelineWorker.pollOnce();   // 미종결 잔존 + FAILED 관측 → 원인이 정확한 JOB_FAILED로 종결

        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        // finished였던 job만 행을 얻는다 — 미종결 job-2의 부재는 attempt errorCode가 설명한다(설계 §4.4).
        assertThat(terraformResultRepository.findAll())
                .extracting(TerraformResult::getJobId)
                .containsExactly("job-1");
    }

    @Test
    void aTransientPollErrorRidesExecutionTimeoutWithoutBurningFailCountAndKeepsPollingSiblings() {
        // 폴 전송 실패는 즉시 실패가 아니다: 임계 미만이면 미종결로 두고, 형제 job은 abort 없이 계속 관측되며,
        // task.failCount는 이 경로로 오르지 않는다. 기본 설정에선 임계(10)에 닿기 전에 execution-timeout이 먼저 종결한다.
        Pipeline pipeline = creator.create("e-poll-transient", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-err\",\"job-ok\"]");   // err를 먼저 둬 abort-on-first 회귀를 잡는다
        infraManagerClient.onPollByJob(jobId -> {
            if (jobId.equals("job-ok")) {
                return TerraformPoll.success("COMPLETED");
            }
            throw new CallFailedException("500");
        });

        pipelineWorker.pollOnce();   // dispatch (attempt 1)
        pipelineWorker.pollOnce();   // poll: job-err 전송실패(누적 1), job-ok 성공 관측 → 미종결(대기)
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline, 0).getFailCount()).isZero();   // 전송실패가 failCount를 태우지 않는다
        assertThat(terraformJobStateRepository.findAll())
                .extracting(TerraformJobState::getJobId, TerraformJobState::getLastState,
                        TerraformJobState::getLastError, TerraformJobState::getCallErrorCount)
                .containsExactlyInAnyOrder(
                        tuple("job-ok", "COMPLETED", null, 0),      // err가 먼저여도 형제는 관측됐다(no abort-on-first)
                        tuple("job-err", null, "500", 1));

        clock.advance(Duration.ofHours(1));   // executionTimeout(PT50M) 초과
        pipelineWorker.pollOnce();   // 미종결 job-err 잔존 + FAILED 관측 없음 → EXECUTION_TIMEOUT(재시도 가능)
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(terraformResultRepository.findAll())   // finished였던 job-ok만 행을 얻는다
                .extracting(TerraformResult::getJobId, TerraformResult::isSucceeded)
                .containsExactly(tuple("job-ok", true));
    }

    @Test
    void aJobExceedingThePollErrorBudgetConcludesJobFailedWithABodylessResult() {
        // 누적 전송 실패가 임계(10)를 넘으면 그 job을 관측 불능으로 확정한다 → JOB_FAILED. execution-timeout이
        // 먼저 끊지 않도록 task별 타임아웃을 크게 오버라이드해 임계 발화 경로만 격리한다.
        Pipeline pipeline = creator.create("e-poll-budget", PipelineType.DELETE);
        Task terraform = task(pipeline, 0);
        terraform.setExecutionTimeout(Duration.ofHours(100));
        taskRepository.save(terraform);
        AtomicInteger logFetches = new AtomicInteger();
        infraManagerClient.onPoll(() -> { throw new CallFailedException("500"); });
        infraManagerClient.onResult(() -> {   // terraform log는 폴과 별개의 명시적 API call로 조회한다 — 여기서도 실패 → body 없는 행
            logFetches.incrementAndGet();
            throw new CallFailedException("500");
        });

        pipelineWorker.pollOnce();   // dispatch (attempt 1)
        for (int poll = 1; poll <= MAX_TF_POLL_ERRORS - 1; poll++) {
            clock.advance(Duration.ofMinutes(11));   // polling_interval 경과 후 재폴 (deadline 이내)
            pipelineWorker.pollOnce();               // 누적 1..9 → 임계 미만 → 미종결
            assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(task(pipeline, 0).getFailCount()).isZero();
        }

        clock.advance(Duration.ofMinutes(11));
        pipelineWorker.pollOnce();   // 10번째 전송실패 → 임계 도달 → job 실패 확정 → JOB_FAILED(재시도 가능)
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        // 원인 텍스트가 정상 응답 FAILED가 아니라 폴 임계 초과(관측 불능)임을 구분해 남긴다
        assertThat(attemptNo(pipeline, 0, 1).getFailureDetail()).contains("unreachable after poll budget");
        assertThat(terraformJobStateRepository.findAll()).singleElement()
                .extracting(TerraformJobState::getCallErrorCount).isEqualTo(MAX_TF_POLL_ERRORS);
        // 관측 불능 job에도 terraform log를 명시적 API call(terraformJobResult)로 조회한다 — 폴로 얻은 값이 아니다.
        assertThat(logFetches.get()).isGreaterThanOrEqualTo(1);
        // 그 결과 result 행을 얻는다: succeeded=false, 본문 조회도 실패했으므로 result=null → has_body=false
        assertThat(terraformResultRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getJobId()).isEqualTo("job-1");
            assertThat(row.isSucceeded()).isFalse();
            assertThat(row.getResult()).isNull();
        });
    }

    @Test
    void anExhaustedJobStaysFailedAndIsNotRepolledWhileASiblingIsStillRunning() {
        // 임계를 넘긴 job은 형제 대기로 판정이 미뤄지는 turn에도 실패로 남고 다시 폴되지 않는다(sticky).
        // 재폴로 일시 회복이 확정된 실패를 뒤집으면 안 된다(codex P1 회귀 가드).
        Pipeline pipeline = creator.create("e-sticky", PipelineType.DELETE);
        Task terraform = task(pipeline, 0);
        terraform.setExecutionTimeout(Duration.ofHours(100));   // 임계 발화 경로만 격리(timeout 배제)
        taskRepository.save(terraform);
        infraManagerClient.onDispatch(() -> "[\"job-err\",\"job-ok\"]");
        infraManagerClient.onPollByJob(jobId -> {
            if (jobId.equals("job-ok")) {
                return TerraformPoll.running("RUNNING");   // 형제는 계속 running
            }
            throw new CallFailedException("500");          // job-err는 매 turn 전송 실패
        });

        pipelineWorker.pollOnce();   // dispatch (attempt 1)
        for (int poll = 1; poll <= MAX_TF_POLL_ERRORS; poll++) {
            clock.advance(Duration.ofMinutes(11));
            pipelineWorker.pollOnce();   // job-err 누적 1..10, job-ok running → 형제 대기로 미종결
            assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        }
        assertThat(task(pipeline, 0).getFailCount()).isZero();   // 여기까지 failCount 불변

        // job-err가 이제 성공을 돌려줘도(일시 회복) sticky 실패라 다시 폴되면 안 된다 — 폴되면 테스트가 시끄럽게 깨진다.
        infraManagerClient.onPollByJob(jobId -> {
            if (jobId.equals("job-ok")) {
                return TerraformPoll.success("COMPLETED");
            }
            throw new AssertionError("exhausted job-err must not be re-polled");
        });
        clock.advance(Duration.ofMinutes(11));
        pipelineWorker.pollOnce();   // job-err sticky 실패(미폴) + job-ok 성공 → 전원 terminal → JOB_FAILED

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(terraformResultRepository.findAll())
                .extracting(TerraformResult::getJobId, TerraformResult::isSucceeded)
                .containsExactlyInAnyOrder(tuple("job-err", false), tuple("job-ok", true));
    }

    @Test
    void aNullPollStatusCountsTowardThePerJobErrorBudget() {
        // 상태 없음(null poll)도 전송 실패와 같은 관측 불능이라 누적 카운트에 반영된다(임계 미만이라 미종결).
        Pipeline pipeline = creator.create("e-null-poll", PipelineType.DELETE);
        infraManagerClient.onPoll(() -> null);

        pipelineWorker.pollOnce();   // dispatch
        pipelineWorker.pollOnce();   // poll이 null 반환 → call error 누적 1

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task(pipeline, 0).getFailCount()).isZero();
        assertThat(terraformJobStateRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getCallErrorCount()).isEqualTo(1);
            assertThat(row.getLastError()).isEqualTo("InfraManager returned no status for job job-1");
        });
    }

    @Test
    void installPromotesTheSuccessorInTheSameReportAndFinishes() {
        // AWS install recipe = 서비스 plan·apply → 네트워크 준비 확인 → BDC common plan·apply → BDC service level plan·apply.
        Pipeline pipeline = creator.create("e-install", PipelineType.INSTALL);
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
        infraManagerClient.onCheck(() -> true);
        for (int sequence = 1; sequence <= 6; sequence++) {
            assertThat(task(pipeline, sequence).getStatus()).isEqualTo(TaskStatus.BLOCKED);
        }

        pipelineWorker.pollOnce();   // dispatch 서비스 plan terraform
        pipelineWorker.pollOnce();   // poll plan success → DONE + promote apply BLOCKED→READY (same write-back 트랜잭션)
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.READY);

        pipelineWorker.pollOnce();   // dispatch 서비스 apply terraform
        pipelineWorker.pollOnce();   // poll apply success → DONE + promote condition BLOCKED→READY
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 2).getStatus()).isEqualTo(TaskStatus.READY);

        pipelineWorker.pollOnce();   // dispatch condition (NONE) → IN_PROGRESS
        pipelineWorker.pollOnce();   // poll condition met → DONE + promote BDC common plan
        assertThat(task(pipeline, 2).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task(pipeline, 3).getStatus()).isEqualTo(TaskStatus.READY);

        for (int i = 0; i < 8; i++) {
            pipelineWorker.pollOnce();   // BDC common plan·apply → BDC service level plan·apply (각 dispatch + poll)
        }
        assertThat(task(pipeline, 6).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.DONE);
    }

    @Test
    void conditionNotMetRetriesThenFailsAtMaxWithConditionNotMet() {
        Pipeline pipeline = creator.create("e-cond-fail", PipelineType.INSTALL);
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));
        infraManagerClient.onCheck(() -> false);   // condition never met → each poll is a failed poll

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);   // plan succeeded first
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.DONE);   // then apply
        Task condition = task(pipeline, 2);
        assertThat(condition.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(condition.getErrorCode()).isEqualTo(ErrorCode.CONDITION_NOT_MET);
        assertThat(condition.getFailCount()).isEqualTo(2);   // bounded by maxFailCount, not a wall-clock ttl
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void terraformFailureRetriesThenFailsAtMaxAndCascadeCancels() {
        Pipeline pipeline = creator.create("e-fail", PipelineType.INSTALL);
        infraManagerClient.onPoll(() -> TerraformPoll.failure("FAILED", null));

        runUntilTerminal(pipeline);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(2);
        assertThat(task(pipeline, 1).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aLostDispatchResponseRidesExecutionTimeoutThenSharesTheFailCount() {
        Pipeline pipeline = creator.create("e-lost", PipelineType.DELETE);
        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS, response recorded

        TaskAttempt attempt = attempt(pipeline, 0);
        attempt.setResponse(null);
        taskAttemptRepository.saveAndFlush(attempt);

        clock.advance(Duration.ofHours(1));
        pipelineWorker.pollOnce();   // response lost → executionTimeout fallthrough → retryable EXECUTION_TIMEOUT

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
    }

    @Test
    void aMissingAttemptRowRidesExecutionTimeoutViaTheTypeRecoveryPolicy() {
        Pipeline pipeline = creator.create("e-lost-attempt", PipelineType.DELETE);
        pipelineWorker.pollOnce();   // dispatch → IN_PROGRESS, attempt 행 기록됨

        taskAttemptRepository.delete(attempt(pipeline, 0));   // 관찰 유실 — attempt 행 자체가 사라짐
        clock.advance(Duration.ofHours(1));                   // executionTimeout(PT50M) 초과
        pipelineWorker.pollOnce();   // attempt 유실 → checkWithoutAttempt → retryable EXECUTION_TIMEOUT

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(11));                // polling_interval(PT10M) 경과 → 재dispatch due
        pipelineWorker.pollOnce();   // 멱등 재dispatch → attempt 2
        taskAttemptRepository.delete(attempt(pipeline, 0));   // attempt 2도 유실
        clock.advance(Duration.ofHours(1));
        pipelineWorker.pollOnce();   // failCount 예산 소진(maxFailCount=2) → 판별 코드가 task에 남는다

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.EXECUTION_TIMEOUT);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(2);   // 유실 재시도도 같은 failCount 예산을 쓴다
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aThrowingDispatchIsCheckErrorAndRetries() {
        Pipeline pipeline = creator.create("e-throw", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> { throw new CallFailedException("503"); });

        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(1);
        assertThat(attemptNo(pipeline, 0, 1).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        // 예외 메시지("왜")가 attempt 행에 함께 영속된다 — 서버 로그 없이도 원인을 볼 수 있다
        assertThat(attemptNo(pipeline, 0, 1).getFailureDetail()).isEqualTo("503");
    }

    @Test
    void aRawRuntimeExceptionFromTheClientPropagatesOutOfProcess() {
        Pipeline pipeline = creator.create("e-bug", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> { throw new IllegalStateException("a real bug"); });
        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();

        assertThatThrownBy(() -> pipelineWorker.process(claim)).isInstanceOf(RuntimeException.class);

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(0);
    }

    @Test
    void aMalformedDispatchResponseFailsTheTaskOutright() {
        Pipeline pipeline = creator.create("e-malformed", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "not-json");

        pipelineWorker.pollOnce();   // dispatch records "not-json"
        pipelineWorker.pollOnce();   // check cannot deserialize → CHECK_ERROR outright

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
        assertThat(task(pipeline, 0).getFailCount()).isEqualTo(0);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
        // 같은 CHECK_ERROR라도 원인 텍스트로 전송 실패와 malformed 응답을 구분할 수 있다
        assertThat(attemptNo(pipeline, 0, 1).getFailureDetail()).startsWith("malformed dispatch response:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[null]", "[\"\"]"})
    void aResponseWithNoUsableJobIdsFailsTheTaskOutright(String response) {
        Pipeline pipeline = creator.create("e-no-jobs", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> response);

        pipelineWorker.pollOnce();
        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.CHECK_ERROR);
    }

    @Test
    void nJobsCompleteOnlyWhenAllSucceed() {
        Pipeline pipeline = creator.create("e-n", PipelineType.DELETE);
        infraManagerClient.onDispatch(() -> "[\"job-1\",\"job-2\",\"job-3\"]");
        infraManagerClient.onPoll(() -> TerraformPoll.success("COMPLETED"));

        pipelineWorker.pollOnce();
        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.RUNNING);   // 나머지 destroy 2단계가 남아 있다
    }

    @Test
    void anUnknownTaskNameFailsAsUnknownTask() {
        Pipeline pipeline = creator.create("e-unknown", PipelineType.DELETE);
        Task task = task(pipeline, 0);
        task.setTaskName("NO_SUCH_TYPE");
        taskRepository.save(task);

        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_TASK);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    @Test
    void aLegacyOperationValueFailsAsUnknownTaskInsteadOfCrashingTheRead() {
        // 카탈로그에서 제거된 옛 operation 값 — converter가 null로 열화하고, StepRunner의 row 캐시 대조가
        // 정의 불일치로 보고 외부 호출 없이 UNKNOWN_TASK로 끊는다(@Enumerated였다면 조회 자체가 터진다).
        Pipeline pipeline = creator.create("e-legacy-op", PipelineType.DELETE);
        jdbcTemplate.update("update task set operation = 'DESTROY_NETWORK' where id = ?", task(pipeline, 0).getId());

        pipelineWorker.pollOnce();

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(pipeline, 0).getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_TASK);
        assertThat(status(pipeline)).isEqualTo(PipelineStatus.FAILED);
    }

    // ── claim / lease / fencing (Decision 2, 4, 5) ───────────────────────────────────────────────

    @Test
    void creationSeedsNextDueAtSoThePipelineIsImmediatelyClaimable() {
        Pipeline pipeline = creator.create("c-seed", PipelineType.DELETE);
        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getNextDueAt()).isEqualTo(START);
        assertThat(pipelineClaimer.claimOneDue()).isPresent();
    }

    @Test
    void aClaimStampsAFreshTokenAndLeaseAndBlocksASecondClaim() {
        creator.create("c-claim", PipelineType.DELETE);

        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();

        Pipeline claimed = pipelineRepository.findById(claim.pipelineId()).orElseThrow();
        assertThat(claimed.getClaimedBy()).isEqualTo(claim.token());
        assertThat(claimed.getClaimedUntil()).isEqualTo(START.plus(LEASE));
        assertThat(pipelineClaimer.claimOneDue()).isEmpty();   // live lease → not claimable
    }

    @Test
    void anExpiredLeaseIsReclaimedWithADifferentToken() {
        creator.create("c-reclaim", PipelineType.DELETE);
        String first = pipelineClaimer.claimOneDue().orElseThrow().token();

        clock.advance(LEASE.plusSeconds(1));
        String second = pipelineClaimer.claimOneDue().orElseThrow().token();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void aSuccessfulDispatchReleasesTheClaimAndAdvancesNextDueAt() {
        Pipeline pipeline = creator.create("c-release", PipelineType.DELETE);

        pipelineWorker.pollOnce();   // dispatch + report

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(after.getClaimedBy()).isNull();
        assertThat(after.getClaimedUntil()).isNull();
        assertThat(after.getNextDueAt()).isNotNull();
    }

    @Test
    void aStaleTokenReportNoOpsAfterReclaim() {
        Pipeline pipeline = creator.create("c-stale", PipelineType.DELETE);
        String stale = pipelineClaimer.claimOneDue().orElseThrow().token();
        clock.advance(LEASE.plusSeconds(1));
        String fresh = pipelineClaimer.claimOneDue().orElseThrow().token();
        assertThat(fresh).isNotEqualTo(stale);

        pipelineWorker.process(new Claim(pipeline.getId(), stale));   // old token

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.READY);   // untouched
        assertThat(after.getClaimedBy()).isEqualTo(fresh);                       // fresh claim intact
    }

    @Test
    void aMatchingTokenReportStillAppliesAfterLeaseExpiryWhenNobodyReclaimed() {
        Pipeline pipeline = creator.create("c-token-only", PipelineType.DELETE);
        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();
        clock.advance(LEASE.plusSeconds(1));   // lease expired, but token unchanged and nobody reclaimed

        pipelineWorker.process(claim);   // token-only guard → applies

        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    // ── cancel (Decision 6) ──────────────────────────────────────────────────────────────────────

    @Test
    void cancelCaseAIdleTerminatesImmediatelyAndClearsTheClaim() {
        Pipeline pipeline = creator.create("x-idle", PipelineType.INSTALL);

        Pipeline cancelled = control.cancel(pipeline.getId());

        assertThat(cancelled.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(cancelled.getActiveTarget()).isNull();
        assertThat(cancelled.getClaimedBy()).isNull();
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()))
                .extracting(Task::getStatus).containsOnly(TaskStatus.CANCELLED);
    }

    @Test
    void cancelCaseAExpiredLeaseStillTerminatesImmediately() {
        Pipeline pipeline = creator.create("x-expired", PipelineType.DELETE);
        pipelineClaimer.claimOneDue();
        clock.advance(LEASE.plusSeconds(1));

        control.cancel(pipeline.getId());

        Pipeline after = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(after.getClaimedBy()).isNull();
    }

    @Test
    void cancelCaseBLiveLeaseOnlyRaisesTheFlagThenTheClaimHolderTerminalizes() {
        Pipeline pipeline = creator.create("x-live", PipelineType.DELETE);
        Claim claim = pipelineClaimer.claimOneDue().orElseThrow();

        control.cancel(pipeline.getId());

        Pipeline flagged = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(flagged.getStatus()).isEqualTo(PipelineStatus.RUNNING);   // API did not write status
        assertThat(flagged.isCancelRequested()).isTrue();
        assertThat(flagged.getNextDueAt()).isEqualTo(START);

        pipelineWorker.process(claim);   // claim holder observes the flag at its safe point

        Pipeline done = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(task(pipeline, 0).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void aStaleStragglerCannotResurrectAfterCaseACancel() {
        Pipeline pipeline = creator.create("x-resurrect", PipelineType.DELETE);
        Claim straggler = pipelineClaimer.claimOneDue().orElseThrow();
        clock.advance(LEASE.plusSeconds(1));
        control.cancel(pipeline.getId());   // Case A clears the token

        pipelineWorker.process(straggler);   // old token → no-op

        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20 && !status(pipeline).isTerminal(); i++) {
            pipelineWorker.pollOnce();
            clock.advance(Duration.ofHours(1));
        }
        assertThat(status(pipeline).isTerminal())
                .as("pipeline did not reach a terminal state in 20 polls")
                .isTrue();
    }

    private Task task(Pipeline pipeline, int sequence) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(sequence);
    }

    private TaskAttempt attempt(Pipeline pipeline, int sequence) {
        return attemptNo(pipeline, sequence, task(pipeline, sequence).getFailCount() + 1);
    }

    private TaskAttempt attemptNo(Pipeline pipeline, int sequence, int attemptNumber) {
        return taskAttemptRepository.findByTaskIdAndAttemptNumber(task(pipeline, sequence).getId(), attemptNumber).orElseThrow();
    }

    private PipelineStatus status(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus();
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
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2)
                    .maxTerraformPollCallErrors(MAX_TF_POLL_ERRORS)
                    .startDelay(Duration.ZERO)
                    .build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2).leaseDuration(LEASE).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }
    }
}
