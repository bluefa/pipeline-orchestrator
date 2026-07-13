package com.bff.pipeline.service.task.terraform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TerraformJobState;
import com.bff.pipeline.model.TerraformJobRef;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행-시점 job 상태 recorder의 upsert 시맨틱을 검증한다: 매 폴 제자리 갱신(같은 (task, attempt, job) 1행,
 * poll_count 증가), FAILED job의 실패 사유 기록, 폴 호출 실패의 last_error 기록(직전 상태 유지), 정상 폴의
 * last_error 소거(최신 폴 관점), 외부값 clamp, 행 단위 저장 실패의 관찰 결손 강등(형제 job 무영향).
 *
 * 프로덕션에서 recorder는 트랜잭션 밖(run 단계)에서 돌아 save마다 자체 커밋되고 무결성 위반이 save 시점에 터진다
 * — {@code NOT_SUPPORTED}로 테스트 래핑 트랜잭션을 억제해 같은 시점 의미를 유지한다({@code TerraformResultRecorderTest}와 동일).
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TerraformJobStateRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-03T00:00:00Z");

    @Autowired private TerraformJobStateRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void upsertsOneRowPerJobAndUpdatesStateInPlaceAcrossPolls() {
        recorder().recordObserved(ref("job-1"), TerraformPoll.running("PLANNING"));
        recorder().recordObserved(ref("job-1"), TerraformPoll.success("COMPLETED"));

        assertThat(repository.findAll())
                .extracting(TerraformJobState::getJobId, TerraformJobState::getLastState,
                        TerraformJobState::getPollCount, TerraformJobState::getLastPolledAt)
                .containsExactly(tuple("job-1", "COMPLETED", 2, NOW));
    }

    @Test
    void recordsTheFailureReasonWhenAJobIsObservedFailed() {
        recorder().recordObserved(ref("job-1"),
                TerraformPoll.failure("FAILED", "Error: exit status 1"));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLastState()).isEqualTo("FAILED");
            assertThat(row.getLastFailReason()).isEqualTo("Error: exit status 1");
            assertThat(row.getLastError()).isNull();
        });
    }

    @Test
    void recordsACallErrorKeepingThePriorObservedState() {
        recorder().recordObserved(ref("job-1"), TerraformPoll.running("APPLYING"));
        recorder().recordCallError(ref("job-1"), "infra-manager call failed: 503");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLastState()).isEqualTo("APPLYING");   // 상태 조회를 못 했으니 직전 관측 유지
            assertThat(row.getLastError()).isEqualTo("infra-manager call failed: 503");
            assertThat(row.getPollCount()).isEqualTo(2);
        });
    }

    @Test
    void clearsThePriorCallErrorOnASubsequentGoodPoll() {
        recorder().recordCallError(ref("job-1"), "infra-manager call failed: 503");
        recorder().recordObserved(ref("job-1"), TerraformPoll.success("COMPLETED"));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLastState()).isEqualTo("COMPLETED");
            assertThat(row.getLastError()).isNull();   // 최신 폴엔 오류가 없으니 소거한다
        });
    }

    @Test
    void recordsTheResponseBodyAndKeepsItAcrossACallError() {
        recorder().recordObserved(ref("job-1"),
                TerraformPoll.running("APPLYING").withResponse("{\"terraformState\":\"APPLYING\",\"id\":7}"));
        recorder().recordCallError(ref("job-1"), "infra-manager call failed: 503");

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            // 원문은 마지막 정상 폴 것을 유지한다 — 호출 실패 turn은 body를 못 받으므로 직전 원문을 보존한다.
            assertThat(row.getLastResponse()).isEqualTo("{\"terraformState\":\"APPLYING\",\"id\":7}");
            assertThat(row.getLastError()).isEqualTo("infra-manager call failed: 503");
        });
    }

    @Test
    void accumulatesCallErrorCountAcrossPollsAndReturnsTheRunningTotal() {
        assertThat(recorder().recordCallError(ref("job-1"), "500")).isEqualTo(1);
        assertThat(recorder().recordCallError(ref("job-1"), "503")).isEqualTo(2);

        assertThat(repository.findAll()).singleElement()
                .extracting(TerraformJobState::getCallErrorCount).isEqualTo(2);
    }

    @Test
    void doesNotResetTheCumulativeCallErrorCountOnAGoodPoll() {
        recorder().recordCallError(ref("job-1"), "500");
        recorder().recordObserved(ref("job-1"), TerraformPoll.running("APPLYING"));   // 정상 폴이 사이에 껴도
        int total = recorder().recordCallError(ref("job-1"), "503");

        assertThat(total).isEqualTo(2);   // 누적은 리셋되지 않는다
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getCallErrorCount()).isEqualTo(2);
            assertThat(row.getLastError()).isEqualTo("503");
        });
    }

    @Test
    void clampsOverlongExternalTextToColumnLengths() {
        recorder().recordObserved(ref("job-1"),
                TerraformPoll.failure("F".repeat(40), "r".repeat(600)));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLastState()).hasSize(TerraformJobState.STATE_LENGTH);
            assertThat(row.getLastFailReason()).hasSize(TerraformJobState.DETAIL_LENGTH);
        });
    }

    @Test
    void aRowLevelSaveFailureLosesOnlyThatObservation() {
        // job_id 컬럼(64자)을 넘는 외부 job id → 그 행의 저장만 무결성 위반으로 실패한다. 경계 catch가 관찰
        // 결손으로 강등하므로 예외가 판정 경로로 새지 않고, 형제 job의 행은 그대로 남는다(상태 무관여 계약).
        String overlongJobId = "j".repeat(65);

        recorder().recordObserved(ref(overlongJobId), TerraformPoll.success("COMPLETED"));
        recorder().recordObserved(ref("job-2"), TerraformPoll.success("COMPLETED"));

        assertThat(repository.findAll())
                .extracting(TerraformJobState::getJobId)
                .containsExactly("job-2");
    }

    private TerraformJobStateRecorder recorder() {
        return new TerraformJobStateRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 이 테스트의 모든 관찰은 task 42 / attempt 1 안에서 일어나므로 job id만 달리해 키를 만든다. */
    private TerraformJobRef ref(String jobId) {
        return new TerraformJobRef(task(42L), attempt(1), jobId);
    }

    private Task task(long id) {
        return Task.builder().id(id).build();
    }

    private TaskAttempt attempt(int attemptNumber) {
        return TaskAttempt.builder().attemptNumber(attemptNumber).build();
    }
}
