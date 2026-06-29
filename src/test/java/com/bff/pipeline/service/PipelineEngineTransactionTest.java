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
import com.bff.pipeline.repository.TaskRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Real-transaction proofs (full context, no test-wrapping transaction): {@code advance} commits in its
 * own transaction, and a cancel that commits <em>during</em> an in-flight InfraManager call cannot be
 * clobbered by the stale engine step — the task's {@code @Version} optimistic lock rejects it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rtx;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PipelineEngineTransactionTest {

    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private PipelineEngine engine;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskRepository tasks;
    @Autowired private GatedInfraManagerClient infraManager;

    @Test
    void advanceCommitsInItsOwnTransactionVisibleToAFreshRead() {
        Pipeline pipeline = creator.create("rtx-commit", PipelineType.DELETE);

        engine.advance(pipeline.getId());

        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void aCancelThatCommitsDuringTheInfraManagerClientCallDoesNotClobberCancelled() throws Exception {
        Pipeline pipeline = creator.create("rtx-cancel", PipelineType.DELETE);
        engine.advance(pipeline.getId());

        CountDownLatch callInFlight = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        infraManager.gate(callInFlight, cancelCommitted, TerraformPoll.success());

        Thread worker = new Thread(() -> {
            try {
                engine.advance(pipeline.getId());
            } catch (RuntimeException expected) {
            }
        });
        worker.start();

        assertThat(callInFlight.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            control.cancel(pipeline.getId());
        } finally {
            cancelCommitted.countDown();
        }
        worker.join(5_000);

        assertThat(pipelines.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
    }

    /** A gated {@link InfraManagerClient}: the poll blocks until the test lands a cancel and releases it. */
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
        @Bean
        GatedInfraManagerClient infraManagerClient() {
            return new GatedInfraManagerClient();
        }
    }
}
