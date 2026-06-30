package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.client.TimeBoundedInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
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
 * ADR-021 소프트 캡(soft cap) 테스트이다. {@code runningPipelineCap}(어드미션 게이트)과
 * {@code slotCap}(Terraform 슬롯 게이트)이 각각 동시 클레임과 동시 TF 디스패치를 제한함을
 * 검증한다. Decision 7에 따라 소프트 게이트이므로 오버슈트는 허용되지만,
 * 단일 스레드 환경에서는 정확히 캡 경계에서 차단이 발생한다. 슬롯 게이트는 클레임을 해제하여
 * 워커가 다른 파이프라인을 자유롭게 처리할 수 있도록 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TaskMachine.class, TaskTypeRegistry.class, TerraformTask.class,
        ConditionCheckTask.class, Observations.class, TaskCanceller.class, PipelineCreator.class,
        PipelineInserter.class, PipelineControl.class, Recipes.class,
        StepRunner.class, StepReporter.class, PipelineClaimer.class, PipelineWorker.class,
        TimeBoundedInfraManagerClient.class,
        PipelineSoftCapTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineSoftCapTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineClaimer claimer;
    @Autowired private PipelineWorker worker;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private PipelineWorkerTest.MutableClock clock;
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
    void theAdmissionCapBlocksASecondConcurrentClaim() {
        creator.create("cap-a", PipelineType.DELETE);
        creator.create("cap-b", PipelineType.DELETE);
        PipelineClaimer.Claim first = claimer.claimOneDue().orElseThrow();
        assertThat(first).isNotNull();
        assertThat(claimer.claimOneDue()).isEmpty();
    }

    @Test
    void theTerraformSlotGateReschedulesAndReleasesTheClaimWhenNoSlotIsFree() {
        Pipeline a = creator.create("slot-a", PipelineType.DELETE);
        Task at = tasks.findByPipelineIdOrderBySequenceAsc(a.getId()).get(0);
        at.setStatus(TaskStatus.IN_PROGRESS);
        at.setJobId("job-a");
        tasks.save(at);
        Pipeline arow = pipelines.findById(a.getId()).orElseThrow();
        arow.setNextDueAt(START.plus(Duration.ofDays(1)));
        pipelines.save(arow);
        Pipeline b = creator.create("slot-b", PipelineType.DELETE);
        worker.pollOnce();
        Task bt = tasks.findByPipelineIdOrderBySequenceAsc(b.getId()).get(0);
        assertThat(bt.getStatus()).isEqualTo(TaskStatus.READY);
        assertThat(bt.getJobId()).isNull();
        Pipeline brow = pipelines.findById(b.getId()).orElseThrow();
        assertThat(brow.getNextDueAt()).isEqualTo(START.plus(Duration.ofSeconds(1)));
        assertThat(brow.getClaimedBy()).isNull();
    }

    @TestConfiguration
    static class Wiring {

        @Bean
        PipelineWorkerTest.MutableClock clock() {
            return new PipelineWorkerTest.MutableClock(START);
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
                    .runningPipelineCap(1)
                    .slotCap(1)
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
