package com.bff.pipeline.service.task.terraform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TerraformResult;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.repository.TerraformResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * postCheck recorder의 저장 시맨틱(설계 §4.4 쓰기 경로)을 검증한다: job별 독립(부분 실패 격리), 조회 실패의
 * 포인터 행 강등, 멱등(재실행 시 기존 행 skip), tail-only 절단, 저장 실패의 관찰 결손 강등. 상태 무관여
 * (판정에 영향 없음)는 {@code PipelineExecutionTest}의 e2e가 함께 고정한다.
 *
 * 프로덕션에서 recorder는 트랜잭션 밖(run 단계)에서 돌아 save마다 자체 커밋되고 무결성 위반이 save 시점에
 * 터진다 — {@code NOT_SUPPORTED}로 테스트 래핑 트랜잭션을 억제해 같은 시점 의미를 유지한다(지연 flush가
 * 위반을 recorder의 catch 밖으로 밀어내지 않게).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TerraformResultRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-03T00:00:00Z");

    @Autowired private TerraformResultRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private final FakeInfraManagerClient infraManagerClient = new FakeInfraManagerClient();

    @Test
    void recordsOneRowPerFinishedJob() {
        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry("job-1", TerraformPoll.success("gs://r/1")),
                Map.entry("job-2", TerraformPoll.failure("gs://r/2"))));

        assertThat(repository.findAll())
                .extracting(TerraformResult::getJobId, TerraformResult::isSucceeded, TerraformResult::getResultPath,
                        TerraformResult::getResult, TerraformResult::getCreatedAt)
                .containsExactly(
                        tuple("job-1", true, "gs://r/1", "terraform: ok", NOW),
                        tuple("job-2", false, "gs://r/2", "terraform: ok", NOW));
    }

    @Test
    void aFetchFailureForOneJobKeepsAPointerRowAndTheOtherJobsBody() {
        AtomicInteger calls = new AtomicInteger();
        infraManagerClient.onResult(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new CallFailedException("infra-manager down");   // job-1의 본문 조회만 실패
            }
            return "Apply complete!";
        });

        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry("job-1", TerraformPoll.failure("gs://r/1")),
                Map.entry("job-2", TerraformPoll.success(null))));

        assertThat(repository.findAll())
                .extracting(TerraformResult::getJobId, TerraformResult::getResult, TerraformResult::getResultPath)
                .containsExactly(
                        tuple("job-1", null, "gs://r/1"),          // 포인터 행 — 본문만 결손
                        tuple("job-2", "Apply complete!", null));  // 나머지 job은 영향 없다
    }

    @Test
    void skipsJobsAlreadyRecordedOnARerun() {
        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry("job-1", TerraformPoll.success(null))));

        infraManagerClient.onResult(() -> "a different body on the healing turn");
        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry("job-1", TerraformPoll.success(null)),
                Map.entry("job-2", TerraformPoll.success(null))));   // 자기치유 turn — 빠진 행만 채운다

        assertThat(repository.findAll())
                .extracting(TerraformResult::getJobId, TerraformResult::getResult)
                .containsExactly(
                        tuple("job-1", "terraform: ok"),
                        tuple("job-2", "a different body on the healing turn"));
    }

    @Test
    void truncatesTheBodyTailFirstBeyondTheMediumtextBudget() {
        String tail = "Error: the actual failure reason lives at the end";
        String body = "H".repeat(TerraformResultRecorder.MAX_RESULT_CHARS) + tail;
        infraManagerClient.onResult(() -> body);

        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry("job-1", TerraformPoll.failure(null))));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.isTruncated()).isTrue();
            assertThat(row.getResult()).hasSize(TerraformResultRecorder.MAX_RESULT_CHARS);
            assertThat(row.getResult()).endsWith(tail);   // tail 우선 — 실패 원인은 로그 끝에 몰린다
        });
    }

    @Test
    void aResultPathBeyondTheColumnLengthIsClampedNotFatal() {
        String overlongPath = "gs://results/" + "p".repeat(TerraformResult.RESULT_PATH_LENGTH);

        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry("job-1", TerraformPoll.success(overlongPath))));

        assertThat(repository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getResultPath())
                        .hasSize(TerraformResult.RESULT_PATH_LENGTH)
                        .isEqualTo(overlongPath.substring(0, TerraformResult.RESULT_PATH_LENGTH)));
    }

    @Test
    void aRowLevelSaveFailureLosesOnlyThatObservation() {
        // job_id 컬럼(64자)을 넘는 외부 job id → 그 행의 저장만 무결성 위반으로 실패한다. 경계 catch가 관찰
        // 결손으로 강등하므로 예외가 판정 경로로 새지 않고, 형제 job의 행은 그대로 남는다(상태 무관여 계약).
        String overlongJobId = "j".repeat(65);

        recorder().recordFinishedJobs(task(42L), attempt(1), finished(
                Map.entry(overlongJobId, TerraformPoll.success(null)),
                Map.entry("job-2", TerraformPoll.success(null))));

        assertThat(repository.findAll())
                .extracting(TerraformResult::getJobId)
                .containsExactly("job-2");
    }

    private TerraformResultRecorder recorder() {
        return new TerraformResultRecorder(infraManagerClient, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Task task(long id) {
        return Task.builder().id(id).operation(TaskOperation.AWS_SERVICE_TF_APPLY).build();
    }

    private TaskAttempt attempt(int attemptNumber) {
        return TaskAttempt.builder().attemptNumber(attemptNumber).build();
    }

    @SafeVarargs
    private static Map<String, TerraformPoll> finished(Map.Entry<String, TerraformPoll>... polls) {
        Map<String, TerraformPoll> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, TerraformPoll> poll : polls) {
            ordered.put(poll.getKey(), poll.getValue());
        }
        return ordered;
    }
}
