package com.bff.pipeline.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.client.InfraManagerClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link PipelineScheduler}의 순수 로직 단위 테스트(스케줄러 스레드/풀 없이). backoff 케이던스, jitter 경계,
 * nearest-due 상한, 그리고 drain의 실패 격리(claim 실패는 drain 종료, process 실패는 격리, interrupt는 전파)를 검증한다.
 */
class PipelineSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-06-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static ExecutionSettings settings(double jitterRatio) {
        return ExecutionSettings.builder()
                .workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
                .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(jitterRatio)
                .build();
    }

    private PipelineScheduler scheduler(ExecutionSettings settings, PipelineClaimer claimer, PipelineWorker worker) {
        return new PipelineScheduler(worker, claimer, null, settings, Duration.ofSeconds(5), CLOCK);
    }

    @Test
    void emptySweepsGrowGeometricallyCapAtMaxAndResetOnWork() {
        PipelineScheduler scheduler = scheduler(settings(0.0), null, null);

        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofMillis(200));
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofMillis(400));
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofMillis(800));
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(1));   // capped at backoffMax/maxIdleSleep
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(1));
        assertThat(scheduler.nextDelay(true)).isEqualTo(Duration.ofSeconds(1));     // work found → pollInterval, backoff reset
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofMillis(200));   // grew from backoffBase again
    }

    @Test
    void jitterStaysWithinRatioAndFloorsAtOneMilli() {
        PipelineScheduler scheduler = scheduler(settings(0.2), null, null);
        for (int i = 0; i < 200; i++) {
            long millis = scheduler.applyJitter(Duration.ofMillis(1000)).toMillis();
            assertThat(millis).isBetween(800L, 1200L);
        }
        assertThat(scheduler.applyJitter(Duration.ofMillis(1)).toMillis()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void capToNearestDueCapsToTheSoonerDueAndIsANoOpOtherwise() {
        Duration delay = Duration.ofSeconds(5);
        assertThat(PipelineScheduler.capToNearestDue(delay, Optional.of(NOW.plusSeconds(2)), NOW))
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(PipelineScheduler.capToNearestDue(delay, Optional.of(NOW.plusSeconds(9)), NOW))
                .isEqualTo(delay);
        assertThat(PipelineScheduler.capToNearestDue(delay, Optional.empty(), NOW)).isEqualTo(delay);
        assertThat(PipelineScheduler.capToNearestDue(delay, Optional.of(NOW.minusSeconds(1)), NOW)).isEqualTo(delay);
    }

    @Test
    void drainIsolatesAProcessFailureAndContinues() {
        AtomicInteger processed = new AtomicInteger();
        PipelineClaimer claimer = claimerYielding(1L, 2L);
        PipelineWorker worker = new PipelineWorker(null, null, null, null, null, null, null) {
            @Override public void process(Claim claim) {
                processed.incrementAndGet();
                if (claim.pipelineId() == 1L) throw new IllegalStateException("isolated");
            }
        };

        boolean workFound = scheduler(settings(0.0), claimer, worker).drain();

        assertThat(workFound).isTrue();
        assertThat(processed.get()).isEqualTo(2);   // failure on #1 did not stop #2
    }

    @Test
    void drainEndsOnAClaimFailureWithoutHammering() {
        PipelineClaimer claimer = new PipelineClaimer(null, null, null) {
            @Override public Optional<Claim> claimOneDue() { throw new IllegalStateException("db down"); }
        };
        PipelineWorker worker = neverProcess();

        assertThat(scheduler(settings(0.0), claimer, worker).drain()).isFalse();
    }

    @Test
    void aCallInterruptedDuringProcessAbortsTheDrain() {
        PipelineClaimer claimer = claimerYielding(1L);
        PipelineWorker worker = new PipelineWorker(null, null, null, null, null, null, null) {
            @Override public void process(Claim claim) {
                throw new InfraManagerClient.CallInterruptedException();
            }
        };

        assertThatThrownBy(() -> scheduler(settings(0.0), claimer, worker).drain())
                .isInstanceOf(InfraManagerClient.CallInterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();   // flag restored (and cleared for the next test)
    }

    private PipelineClaimer claimerYielding(Long... ids) {
        Deque<Long> queue = new ArrayDeque<>(List.of(ids));
        return new PipelineClaimer(null, null, null) {
            @Override public Optional<Claim> claimOneDue() {
                Long id = queue.poll();
                return id == null ? Optional.empty() : Optional.of(new Claim(id, "t-" + id));
            }
        };
    }

    private PipelineWorker neverProcess() {
        return new PipelineWorker(null, null, null, null, null, null, null) {
            @Override public void process(Claim claim) {
                throw new AssertionError("should not process after a claim failure");
            }
        };
    }
}
