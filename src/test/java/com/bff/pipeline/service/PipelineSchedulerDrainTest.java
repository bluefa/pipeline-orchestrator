package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.client.InfraManagerClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PipelineScheduler#drain()} 및 {@link PipelineScheduler#sweepOnce()} 메서드에 대한
 * 순수 단위 테스트이다. Spring 컨텍스트 없이 {@link PipelineClaimer}와 {@link PipelineWorker}의
 * 서브클래스 테스트 더블을 사용하여 파이프라인별 처리 실패 격리, 클레임 단계 실패 시 해머링 방지,
 * {@link InfraManagerClient.CallInterruptedException} 수신 시 스윕 전체 중단·인터럽트 복원 경로를
 * 각각 독립적으로 검증한다.
 *
 * <p>이 테스트는 {@code drain()}과 {@code sweepOnce()}가 패키지-프라이빗이므로
 * {@code com.bff.pipeline.service} 패키지 안에 위치한다.
 */
class PipelineSchedulerDrainTest {

    private static final ExecutionSettings SETTINGS = ExecutionSettings.builder()
            .workerPerPod(1)
            .leaseDuration(Duration.ofMinutes(2))
            .apiCallTimeout(Duration.ofSeconds(30))
            .runningPipelineCap(100)
            .slotCap(20)
            .slotRetry(Duration.ofSeconds(1))
            .pollInterval(Duration.ofSeconds(1))
            .maxIdleSleep(Duration.ofSeconds(1))
            .backoffBase(Duration.ofMillis(100))
            .backoffMax(Duration.ofSeconds(5))
            .jitterRatio(0.0)
            .build();

    private ExecutorService pool;
    private PipelineScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.stop();
        if (pool != null) pool.shutdownNow();
        Thread.interrupted(); // 잔여 인터럽트 플래그 정리
    }

    // ── Test 1: 처리 실패가 격리되고 드레인이 계속된다 ──────────────────────

    /**
     * 파이프라인 A의 처리가 RuntimeException으로 실패해도 파이프라인 B가 독립적으로 처리되고,
     * drain()이 예외를 던지지 않으며 true(작업 발견)를 반환한다.
     */
    @Test
    void aProcessFailureIsIsolatedAndTheDrainContinues() {
        List<Long> claimedIds = new ArrayList<>();
        List<Long> processedIds = new ArrayList<>();

        PipelineClaimer fakeClaimer = new PipelineClaimer(null, null, null) {
            private int idx = 0;

            @Override
            public Optional<Claim> claimOneDue() {
                int i = idx++;
                if (i == 0) {
                    claimedIds.add(1L);
                    return Optional.of(new Claim(1L, "tok-A"));
                }
                if (i == 1) {
                    claimedIds.add(2L);
                    return Optional.of(new Claim(2L, "tok-B"));
                }
                return Optional.empty();
            }
        };

        PipelineWorker fakeWorker = new PipelineWorker(null, null, null, null, null, null, null) {
            @Override
            public void process(PipelineClaimer.Claim claim) {
                if (claim.pipelineId() == 1L) {
                    throw new RuntimeException("pipeline A blow-up");
                }
                processedIds.add(claim.pipelineId());
            }
        };

        scheduler = new PipelineScheduler(fakeWorker, fakeClaimer, null, SETTINGS, Duration.ofHours(1),
                Clock.systemUTC());
        boolean found = scheduler.drain();

        assertThat(found).isTrue();
        // A와 B 모두 클레임되었다 — 루프가 A 실패 후 B로 계속됐음을 증명한다.
        assertThat(claimedIds).containsExactly(1L, 2L);
        // B만 성공적으로 처리되었다.
        assertThat(processedIds).containsExactly(2L);
    }

    // ── Test 2: 클레임 단계 실패가 드레인을 즉시 종료하고 해머링하지 않는다 ──

    /**
     * claimOneDue()가 RuntimeException(예: DB 장애)을 던지면 drain()이 예외를 전파하지 않고
     * 즉시 false를 반환하며, claimOneDue가 정확히 한 번만 호출된다(해머 루프 없음).
     */
    @Test
    void aClaimPhaseFailureEndsTheDrainWithoutHammering() {
        AtomicInteger claimCallCount = new AtomicInteger();

        PipelineClaimer fakeClaimer = new PipelineClaimer(null, null, null) {
            @Override
            public Optional<Claim> claimOneDue() {
                claimCallCount.incrementAndGet();
                throw new RuntimeException("DB outage");
            }
        };

        scheduler = new PipelineScheduler(null, fakeClaimer, null, SETTINGS, Duration.ofHours(1),
                Clock.systemUTC());
        boolean found = scheduler.drain();

        assertThat(found).isFalse();
        assertThat(claimCallCount.get()).isEqualTo(1);
    }

    // ── Test 3: nearest-due 조회 실패가 루프를 중단시키지 않는다 ────────────

    /**
     * {@link PipelineClaimer#nearestClaimableDueAt()}가 {@link RuntimeException}을 던질 때
     * {@link PipelineScheduler#cappedIdleDelay(Duration)}이 예외를 전파하지 않고
     * 입력 대기 시간을 그대로 반환함을 검증한다.
     * 이는 DB 일시 장애 중에도 자기-재조정 루프가 영구 중단되지 않음을 직접적으로 증명한다.
     */
    @Test
    void aFailedNearestDueLookupFallsBackToTheUncappedDelayAndKeepsTheLoopAlive() {
        PipelineClaimer throwingClaimer = new PipelineClaimer(null, null, null) {
            @Override
            public Optional<Instant> nearestClaimableDueAt() {
                throw new RuntimeException("DB connection lost");
            }
        };

        scheduler = new PipelineScheduler(null, throwingClaimer, null, SETTINGS, Duration.ofHours(1),
                Clock.systemUTC());
        Duration originalDelay = Duration.ofSeconds(3);

        Duration result = scheduler.cappedIdleDelay(originalDelay);

        // 예외가 전파되지 않고, 원래 대기 시간이 그대로 반환된다.
        assertThat(result).isEqualTo(originalDelay);
    }

    // ── Test 4: 처리 중 CallInterrupted가 sweepOnce를 중단시킨다 ──────────────

    /**
     * worker.process()가 {@link InfraManagerClient.CallInterruptedException}을 던지면
     * sweepOnce()가 {@link InterruptedException}을 던지고, 호출 스레드의 인터럽트 플래그가
     * 복원된다. 이는 round-2/3에서 구현된 JVM 종료 신호 전파 경로를 검증한다.
     */
    @Test
    void aCallInterruptedDuringProcessAbortsTheSweep() {
        PipelineClaimer fakeClaimer = new PipelineClaimer(null, null, null) {
            private int count = 0;

            @Override
            public Optional<Claim> claimOneDue() {
                return count++ == 0
                        ? Optional.of(new Claim(99L, "tok-int"))
                        : Optional.empty();
            }
        };

        PipelineWorker fakeWorker = new PipelineWorker(null, null, null, null, null, null, null) {
            @Override
            public void process(PipelineClaimer.Claim claim) {
                throw new InfraManagerClient.CallInterruptedException();
            }
        };

        pool = Executors.newFixedThreadPool(1);
        scheduler = new PipelineScheduler(fakeWorker, fakeClaimer, pool, SETTINGS, Duration.ofHours(1),
                Clock.systemUTC());

        assertThatThrownBy(() -> scheduler.sweepOnce())
                .isInstanceOf(InterruptedException.class);

        // 데코레이터가 호출 스레드의 인터럽트 플래그를 복원했으므로 true여야 한다.
        assertThat(Thread.interrupted()).isTrue();
    }
}
