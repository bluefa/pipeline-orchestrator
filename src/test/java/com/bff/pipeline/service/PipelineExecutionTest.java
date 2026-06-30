package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.client.TimeBoundedInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-021 실행 보장(execution guarantee) 테스트이다. 클레임 원자성, 리스 펜싱 토큰 스탬핑,
 * 만료 리스 재클레임(크래시 복구, Decision 5), 스테일 토큰 가드 라이트백(Decision 4),
 * 취소 Case A(유휴 즉시 종료)·Case B(활성 리스 협력적 취소, Decision 6)를 각각
 * 독립적인 두 개의 커밋 트랜잭션(tx1 클레임 + tx2 보고)으로 검증한다.
 * {@code NOT_SUPPORTED}가 테스트 래핑 트랜잭션을 억제하여 프로덕션과 동일하게 동작한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TaskMachine.class, TaskTypeRegistry.class, TerraformTask.class,
        ConditionCheckTask.class, Observations.class, TaskCanceller.class, PipelineCreator.class,
        PipelineInserter.class, PipelineControl.class, Recipes.class,
        StepRunner.class, StepReporter.class, PipelineClaimer.class, PipelineWorker.class,
        TimeBoundedInfraManagerClient.class,
        PipelineExecutionTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineExecutionTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineClaimer claimer;
    @Autowired private StepReporter reporter;
    @Autowired private PipelineWorker worker;
    @Autowired private PipelineControl control;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private PipelineEngineTest.MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManager;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManager.onDispatch(() -> "job-1");
        infraManager.onPoll(TerraformPoll::running);
        infraManager.onCheck(() -> false);
    }

    @AfterEach
    void clean() {
        checks.deleteAll();
        attempts.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void claimStampsAFreshFencingTokenAndLeaseAndBlocksASecondClaim() {
        Pipeline p = creator.create("t-claim", PipelineType.DELETE);
        PipelineClaimer.Claim claim = claimer.claimOneDue().orElseThrow();
        Pipeline row = pipelines.findById(p.getId()).orElseThrow();
        assertThat(row.getClaimedBy()).isNotNull().isEqualTo(claim.token());
        assertThat(row.getClaimedUntil()).isEqualTo(START.plus(Duration.ofSeconds(30)));
        assertThat(claimer.claimOneDue()).isEmpty();
    }

    @Test
    void anExpiredLeaseIsReclaimedWithADifferentFencingToken() {
        creator.create("t-crash", PipelineType.DELETE);
        PipelineClaimer.Claim first = claimer.claimOneDue().orElseThrow();
        assertThat(claimer.claimOneDue()).isEmpty();
        clock.advance(Duration.ofSeconds(31));
        PipelineClaimer.Claim second = claimer.claimOneDue().orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
    }

    @Test
    void aStaleClaimTokenCannotClobberStateAfterReclaim() {
        Pipeline p = creator.create("t-stale", PipelineType.DELETE);
        PipelineClaimer.Claim stale = claimer.claimOneDue().orElseThrow();
        clock.advance(Duration.ofSeconds(31));
        PipelineClaimer.Claim fresh = claimer.claimOneDue().orElseThrow();
        reporter.report(p.getId(), stale.token(), StepOutcome.dispatched("job-stale"));
        Task t = tasks.findByPipelineIdOrderBySequenceAsc(p.getId()).get(0);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(t.getJobId()).isNull();
        assertThat(pipelines.findById(p.getId()).orElseThrow().getClaimedBy()).isEqualTo(fresh.token());
    }

    @Test
    void cancelCaseAIdleTerminatesImmediatelyAndClearsTheClaim() {
        Pipeline p = creator.create("t-cancel-a", PipelineType.DELETE);
        control.cancel(p.getId());
        Pipeline row = pipelines.findById(p.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(row.getClaimedBy()).isNull();
        assertThat(row.getActiveTarget()).isNull();
        assertThat(row.isCancelRequested()).isFalse();
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(p.getId()).get(0).getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void cancelCaseBLiveLeaseRaisesTheFlagAndTheClaimHolderTerminalizesUnderTheLock() {
        Pipeline p = creator.create("t-cancel-b", PipelineType.DELETE);
        PipelineClaimer.Claim held = claimer.claimOneDue().orElseThrow();
        control.cancel(p.getId());
        Pipeline mid = pipelines.findById(p.getId()).orElseThrow();
        assertThat(mid.getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(mid.isCancelRequested()).isTrue();
        assertThat(mid.getNextDueAt()).isEqualTo(clock.instant());
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(p.getId()).get(0).getStatus())
                .isEqualTo(TaskStatus.READY); // 클레임 이후 어드밴스가 없으므로 여전히 READY이어야 한다.
        reporter.report(p.getId(), held.token(), StepOutcome.dispatched("job-x"));
        Pipeline done = pipelines.findById(p.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(done.getClaimedBy()).isNull();
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(p.getId()).get(0).getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
    }

    /**
     * 리스가 만료된 파이프라인은 {@code cancelIfIdle}의 {@code claimedUntil < now} 분기(Case A)를
     * 통해 즉시 종료 처리된다. 스트래글러 워커의 tx2가 후에 no-op되는 것과는 달리,
     * API 호출자가 만료된 클레임을 직접 회수하여 취소를 완료한다.
     */
    @Test
    void cancelCaseAExpiredLeaseStillTerminatesImmediately() {
        Pipeline p = creator.create("t-cancel-a-expired", PipelineType.DELETE);
        claimer.claimOneDue().orElseThrow(); // token T, claimedUntil = START+30s
        clock.advance(Duration.ofSeconds(31)); // now = START+31s, 리스 만료
        // 재클레임 없이 control.cancel() 호출 — cancelIfIdle이 claimedUntil < now를 만족한다.
        control.cancel(p.getId());

        Pipeline row = pipelines.findById(p.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(row.getClaimedBy()).isNull();
        assertThat(row.getActiveTarget()).isNull();
        assertThat(row.isCancelRequested()).isFalse();
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(p.getId()).get(0).getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
    }

    /**
     * 정상적인 dispatch 이후 tx2(report)가 완료되면 파이프라인의 클레임이 해제되고({@code claimedBy=null},
     * {@code claimedUntil=null}), {@code nextDueAt}이 현재 태스크의 {@code nextCheckAt}(dispatch 직후
     * {@code now})으로 설정된다.
     */
    @Test
    void aSuccessfulDispatchReleasesTheClaimAndAdvancesNextDueAt() {
        Pipeline p = creator.create("t-dispatch-release", PipelineType.DELETE);
        worker.pollOnce(); // tx1: claim → tx2: dispatch → IN_PROGRESS → releaseClaim

        Pipeline row = pipelines.findById(p.getId()).orElseThrow();
        assertThat(row.getClaimedBy()).isNull();
        assertThat(row.getClaimedUntil()).isNull();
        // markInProgress가 task.nextCheckAt = START(clock=START)로 설정하고,
        // releaseClaim이 pipeline.nextDueAt = task.nextCheckAt = START로 설정한다.
        assertThat(row.getNextDueAt()).isEqualTo(START);
    }

    @Test
    void aReportFromAStragglerWhoseLeaseExpiredNoOpsEvenIfItsTokenStillMatches() {
        Pipeline p = creator.create("t-expired-straggler", PipelineType.DELETE);
        PipelineClaimer.Claim claim = claimer.claimOneDue().orElseThrow();   // token T, lease START+30s
        clock.advance(Duration.ofSeconds(31));                                // lease expired, NOT reclaimed → token still T
        reporter.report(p.getId(), claim.token(), StepOutcome.dispatched("job-straggler"));
        Task task = tasks.findByPipelineIdOrderBySequenceAsc(p.getId()).get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.READY);             // NOT advanced to IN_PROGRESS
        assertThat(task.getJobId()).isNull();
        assertThat(pipelines.findById(p.getId()).orElseThrow().getClaimedBy()).isEqualTo(claim.token());  // claim untouched (report no-op'd)
    }

    @TestConfiguration
    static class Wiring {

        @Bean
        PipelineEngineTest.MutableClock clock() {
            return new PipelineEngineTest.MutableClock(START);
        }

        @Bean("infraManagerDelegate")
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .timeToLive(Duration.ofDays(7))
                    .pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2)
                    .build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2)
                    .leaseDuration(Duration.ofSeconds(30))
                    .apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(1000)
                    .slotCap(1000)
                    .slotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1))
                    .maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100))
                    .backoffMax(Duration.ofSeconds(1))
                    .jitterRatio(0.2)
                    .build();
        }

        @Bean(destroyMethod = "shutdown")
        ExecutorService imCallPool() {
            return Executors.newFixedThreadPool(2);
        }
    }
}
