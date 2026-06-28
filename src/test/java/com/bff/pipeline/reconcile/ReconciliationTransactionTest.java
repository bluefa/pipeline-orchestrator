package com.bff.pipeline.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.control.PipelineControl;
import com.bff.pipeline.create.PipelineCreator;
import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.domain.TaskStatus;
import com.bff.pipeline.im.ImClient;
import com.bff.pipeline.im.TerraformPoll;
import com.bff.pipeline.domain.TaskOperation;
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
 * Real-transaction proofs (full context, no test-wrapping transaction): reconcile commits in its own
 * transaction, and a cancel that commits <em>during</em> an in-flight InfraManager call cannot be
 * clobbered by the stale reconcile — the task's {@code @Version} optimistic lock rejects it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rtx;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "pipeline.scheduler-initial-delay=PT30M" // keep the background tick out of the test
})
class ReconciliationTransactionTest {

    @Autowired private PipelineCreator creator;
    @Autowired private PipelineControl control;
    @Autowired private PipelineReconciliation reconciliation;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskRepository tasks;
    @Autowired private GatedImClient im;

    @Test
    void reconcileCommitsInItsOwnTransactionVisibleToAFreshRead() {
        Pipeline pipeline = creator.create("rtx-commit", PipelineType.DELETE);

        reconciliation.reconcile(pipeline.getId()); // dispatch → IN_PROGRESS, committed

        // No surrounding test transaction — a fresh read must see the committed state.
        assertThat(tasks.findByPipelineIdOrderBySeqAsc(pipeline.getId()).getFirst().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void aCancelThatCommitsDuringTheImCallDoesNotClobberCancelled() throws Exception {
        Pipeline pipeline = creator.create("rtx-cancel", PipelineType.DELETE);
        reconciliation.reconcile(pipeline.getId()); // → IN_PROGRESS, committed

        CountDownLatch callInFlight = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        im.gate(callInFlight, cancelCommitted, TerraformPoll.success());

        Thread reconcile = new Thread(() -> {
            try {
                reconciliation.reconcile(pipeline.getId()); // blocks in the poll, then a stale save
            } catch (RuntimeException expected) {
                // The optimistic-lock failure the prod tick swallows in Reconciler#tick.
            }
        });
        reconcile.start();

        assertThat(callInFlight.await(5, TimeUnit.SECONDS)).isTrue();
        control.cancel(pipeline.getId());   // commit CANCELLED while the poll is in flight
        cancelCommitted.countDown();        // let the poll return success
        reconcile.join(5_000);

        assertThat(pipelines.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
    }

    /** A gated {@link ImClient}: the poll blocks until the test lands a cancel and releases it. */
    static final class GatedImClient implements ImClient {
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
            } catch (InterruptedException e) {
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
        GatedImClient imClient() {
            return new GatedImClient();
        }
    }
}
