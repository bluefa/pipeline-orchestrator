package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 실제 트랜잭션 동작을 검증한다(@SpringBootTest 전체 컨텍스트, 테스트 래핑 트랜잭션 없음).
 * {@link PipelineWorker#pollOnce()}가 tx1(claim) + tx2(report)를 각각 독립적으로 커밋함을 확인하며,
 * InfraManager 호출이 진행 중인 <em>도중에</em> 커밋된 cancel이 낡은(stale) 워커 단계에 의해
 * 덮어쓰이지 않음을 검증한다 — tx2 소유권 가드(ownership guard)가 currentTask를 empty로 만들어
 * 결과 적용을 건너뛰고, 파이프라인 상태가 CANCELLED로 유지된다(ADR Decision 4).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rtx;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pipeline.execution.scheduler-initial-delay=PT30M"
})
class PipelineEngineTransactionTest {

    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private PipelineWorker worker;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private GatedInfraManagerClient infraManager;

    @AfterEach
    void clean() {
        checks.deleteAll();
        attempts.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void advanceCommitsInItsOwnTransactionVisibleToAFreshRead() {
        Pipeline pipeline = creator.create("rtx-commit", PipelineType.DELETE);

        worker.pollOnce();

        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void aCancelThatCommitsDuringTheInfraManagerClientCallDoesNotClobberCancelled() throws Exception {
        Pipeline pipeline = creator.create("rtx-cancel", PipelineType.DELETE);
        worker.pollOnce();

        CountDownLatch callInFlight = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        infraManager.gate(callInFlight, cancelCommitted, TerraformPoll.success());

        Thread workerThread = new Thread(() -> worker.pollOnce());
        workerThread.start();

        assertThat(callInFlight.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            control.cancel(pipeline.getId());
        } finally {
            cancelCommitted.countDown();
        }
        workerThread.join(5_000);

        assertThat(pipelines.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
    }

    /** 게이트 제어 방식의 {@link InfraManagerClient} 구현체이다. poll은 테스트가 cancel을 완료하고 게이트를 해제할 때까지 블로킹된다. */
    static final class GatedInfraManagerClient implements InfraManagerClient {
        private volatile CountDownLatch callInFlight;
        private volatile CountDownLatch release;
        private volatile TerraformPoll gatedResult;

        void gate(CountDownLatch callInFlight, CountDownLatch release, TerraformPoll result) {
            this.callInFlight = callInFlight;
            this.release = release;
            this.gatedResult = result;
        }

        @Override
        public String runTerraform(String target, TaskOperation operation) {
            return "job-rtx";
        }

        @Override
        public TerraformPoll terraformJobStatus(String jobId) {
            if (callInFlight == null) {
                return TerraformPoll.running();
            }
            callInFlight.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return gatedResult;
        }

        @Override
        public boolean checkCondition(String target, TaskOperation operation) {
            return false;
        }
    }

    @TestConfiguration
    static class Wiring {
        @Bean("infraManagerDelegate")
        GatedInfraManagerClient infraManagerClient() {
            return new GatedInfraManagerClient();
        }
    }
}
