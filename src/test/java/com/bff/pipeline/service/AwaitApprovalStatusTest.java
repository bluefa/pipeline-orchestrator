package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineStatistics;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.StatisticsPeriod;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.RequestContext;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.execution.PipelineClaimer;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
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
 * 새로 생긴 대기 상태가 상태를 열거하는 모든 자리에 반영됐는지 확인한다. 아직 이 상태로 실행을 옮기는
 * 코드는 없어서 행을 직접 세워 두고 보지만, 확인하려는 것은 "만드는 쪽"이 아니라 "읽는 쪽"이다 —
 * 하나라도 빠뜨리면 대기 중인 실행이 그 화면에서 사라지거나 영영 다시 잡히지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineClaimer.class, PipelineControl.class, TaskCanceller.class, ObservationRecorder.class,
        PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class, PipelineQueryService.class,
        AwaitApprovalStatusTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AwaitApprovalStatusTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");
    private static final Duration WAIT = Duration.ofHours(24);

    @Autowired private PipelineClaimer claimer;
    @Autowired private PipelineControl control;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineQueryService queryService;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private MutableClock clock;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        clock.set(START);
    }

    /** 두 상태 모두 비종단이다 — 종단으로 잘못 분류되면 대기 중인 실행에 종료 알림이 나가고 취소가 막힌다. */
    @Test
    void bothNewStatusesAreNonTerminal() {
        assertThat(PipelineStatus.AWAIT_APPROVAL.isTerminal()).isFalse();
        assertThat(TaskStatus.AWAIT_APPROVAL.isTerminal()).isFalse();
    }

    /**
     * 상태 이름이 컬럼 길이 안에 들어간다. 이름을 늘리면 저장이 조용히 깨지는 것이 아니라 행이 잘려
     * 다시 읽을 때 해석 불가가 되므로, 길이를 코드로 고정한다.
     */
    @Test
    void theNewNamesFitTheStatusColumn() {
        assertThat(PipelineStatus.AWAIT_APPROVAL.name().length()).isLessThanOrEqualTo(16);
        assertThat(TaskStatus.AWAIT_APPROVAL.name().length()).isLessThanOrEqualTo(16);
    }

    /** 대기 중인 실행은 자기가 정한 시각 전에는 잡히지 않는다 — 이것이 "기다리는 동안 자원을 쥐지 않는다"다. */
    @Test
    void aParkedRunIsNotClaimedBeforeItsDueTime() {
        park("await-park");

        assertThat(claimer.claimOneDue()).isEmpty();
    }

    /** 그 시각이 오면 다시 잡히고, 잡는 문장이 같은 자리에서 상태를 RUNNING으로 되돌린다. */
    @Test
    void aParkedRunIsClaimedOnceItIsDueAndComesBackAsRunning() {
        Pipeline parked = park("await-wake");
        clock.set(START.plus(WAIT));

        assertThat(claimer.claimOneDue()).isPresent();
        assertThat(reload(parked).getStatus()).isEqualTo(PipelineStatus.RUNNING);
    }

    /**
     * 대기는 실행 정원을 갉아먹지 않는다. 정원은 상태가 아니라 활성 claim 수로 세는데, 대기 진입은
     * claim을 놓는 쪽이라 24시간을 기다려도 다른 실행이 그만큼 막히지 않는다.
     */
    @Test
    void aParkedRunDoesNotConsumeTheAdmissionCap() {
        park("await-cap-a");

        Pipeline other = creator.create("await-cap-b", PipelineType.DELETE, RequestContext.none());

        assertThat(claimer.claimOneDue()).hasValueSatisfying(claimed ->
                assertThat(claimed.pipelineId()).isEqualTo(other.getId()));
    }

    /** 대기는 RUNNING도 PENDING도 아니고 claim도 없다 — 따로 세지 않으면 현황에서 통째로 사라진다. */
    @Test
    void aParkedRunIsVisibleOnTheLiveDashboard() {
        park("await-live");

        LivePipelineStatistics live = queryService.liveStatistics();

        assertThat(live.awaitApprovalPipelineCount()).isEqualTo(1);
        assertThat(live.runningPipelineCount()).isZero();
        assertThat(live.pendingPipelineCount()).isZero();
        assertThat(live.activeClaimCount()).isZero();
    }

    /** 기간 통계의 합계에도 들어간다 — 빠뜨리면 상태별 합이 전체와 맞지 않는다. */
    @Test
    void aParkedRunIsCountedInThePeriodTotal() {
        park("await-stats");

        PipelineStatistics statistics = queryService.statistics(StatisticsPeriod.ONE_DAY);

        assertThat(statistics.awaitApprovalCount()).isEqualTo(1);
        assertThat(statistics.totalCount()).isEqualTo(1);
    }

    /**
     * 관리자 취소는 언제나 Case A로 들어간다. 대기 중인 실행은 claim을 쥐지 않으므로 취소 요청 플래그만
     * 남기는 경로(Case B)로 빠지면 아무도 그 플래그를 읽지 않아 만료까지 취소가 걸리지 않는다.
     */
    @Test
    void cancellingAParkedRunTakesEffectImmediately() {
        Pipeline parked = park("await-cancel");

        Pipeline cancelled = control.cancel(parked.getId());

        assertThat(cancelled.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(cancelled.isCancelRequested()).isFalse();   // 플래그만 남기고 끝나지 않았다
        assertThat(cancelled.getActiveTarget()).isNull();
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(parked.getId()))
                .extracting(Task::getStatus).containsOnly(TaskStatus.CANCELLED);
    }

    /** 대기 상태의 실행을 세워 둔다 — 이 상태로 옮기는 코드는 다음 PR에서 들어온다. */
    private Pipeline park(String target) {
        Pipeline pipeline = creator.create(target, PipelineType.INSTALL, RequestContext.none());
        Task current = taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst();
        current.setStatus(TaskStatus.AWAIT_APPROVAL);
        taskRepository.save(current);
        Pipeline row = reload(pipeline);
        row.setStatus(PipelineStatus.AWAIT_APPROVAL);
        row.setNextDueAt(START.plus(WAIT));
        return pipelineRepository.save(row);
    }

    private Pipeline reload(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow();
    }

    @TestConfiguration
    static class Wiring {

        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50)).pollingInterval(Duration.ofMinutes(10))
                    .maxFailCount(2).maxTerraformPollCallErrors(10).startDelay(Duration.ZERO).build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(1).terraformSlotCap(1).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }
    }
}
