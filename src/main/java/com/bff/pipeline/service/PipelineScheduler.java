package com.bff.pipeline.service;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.client.InfraManagerClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-021 Decision 1/7: 자기 재예약(self-rescheduling) 적응형 claim-pull 루프이다. 단일 데몬
 * {@link ScheduledExecutorService}가 sweep을 돌리고, 각 sweep은 {@code workerPerPod}개의 drain 작업을
 * 워커 풀에 던져 동시 claim→처리한다. 빈 sweep은 geometric jitter backoff로, 일 있는 sweep은
 * {@code pollInterval}로 다음 sweep을 예약한다 — idle sleep은 nearest-due 시각으로 더 좁혀진다.
 *
 * <p><b>빈 claim ≠ backlog empty</b>: SKIP LOCKED가 동료가 잡은 행을 건너뛰므로, 빈 결과를 유휴로 보지 않고
 * backoff로만 폴링 케이던스를 줄인다. <b>회복력</b>: claim 실패는 drain을 끝내고(난타 방지), 처리 실패는
 * pipeline 단위로 격리되며(claim은 lease 만료로 reclaim), nearest-due 조회 실패는 uncapped fallback로
 * 루프를 살린다. 오직 인터럽트(JVM 종료)만 재예약을 멈춘다.
 */
@Component
public class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    private final PipelineWorker worker;
    private final PipelineClaimer claimer;
    private final ExecutorService pool;
    private final ExecutionSettings settings;
    private final Duration initialDelay;
    private final java.time.Clock clock;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pipeline-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    private Duration idleBackoff;

    public PipelineScheduler(PipelineWorker worker, PipelineClaimer claimer,
            @Qualifier("pipelineWorkerPool") ExecutorService pool, ExecutionSettings settings,
            @Value("${pipeline.execution.scheduler-initial-delay:PT5S}") Duration initialDelay,
            java.time.Clock clock) {
        this.worker = worker;
        this.claimer = claimer;
        this.pool = pool;
        this.settings = settings;
        this.initialDelay = initialDelay;
        this.clock = clock;
        this.idleBackoff = settings.backoffBase();
    }

    @PostConstruct
    void start() {
        scheduler.schedule(this::runSweep, initialDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void runSweep() {
        boolean workFound = false;
        boolean interrupted = false;
        try {
            workFound = sweepOnce();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } finally {
            if (!interrupted) {
                Duration delay = nextDelay(workFound);
                if (!workFound) {
                    delay = cappedIdleDelay(delay);
                }
                scheduler.schedule(this::runSweep, delay.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    boolean sweepOnce() throws InterruptedException {
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < settings.workerPerPod(); i++) {
            futures.add(pool.submit((Callable<Boolean>) this::drain));
        }
        boolean workFound = false;
        for (Future<Boolean> future : futures) {
            try {
                workFound |= future.get();
            } catch (InterruptedException shuttingDown) {
                futures.forEach(f -> f.cancel(true));
                throw shuttingDown;
            } catch (ExecutionException executionFailure) {
                Throwable cause = executionFailure.getCause();
                if (cause instanceof InfraManagerClient.CallInterruptedException) {
                    futures.forEach(f -> f.cancel(true));
                    throw new InterruptedException("worker interrupted");
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                log.warn("drain failed", cause);
            }
        }
        return workFound;
    }

    boolean drain() {
        boolean anyFound = false;
        while (true) {
            if (Thread.interrupted()) {
                throw new InfraManagerClient.CallInterruptedException();
            }
            Optional<PipelineClaimer.Claim> claim;
            try {
                claim = claimer.claimOneDue();
            } catch (InfraManagerClient.CallInterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (RuntimeException claimFailure) {
                log.warn("claim failed; ending drain to avoid hammering the DB", claimFailure);
                return anyFound;
            }
            if (claim.isEmpty()) {
                return anyFound;
            }
            anyFound = true;
            try {
                worker.process(claim.get());
            } catch (InfraManagerClient.CallInterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (RuntimeException processFailure) {
                log.warn("processing pipeline {} failed; it keeps its lease and is reclaimed on expiry",
                        claim.get().pipelineId(), processFailure);
            }
        }
    }

    Duration nextDelay(boolean workFound) {
        if (workFound) {
            idleBackoff = settings.backoffBase();
            return settings.pollInterval();
        }
        Duration doubled = idleBackoff.multipliedBy(2);
        idleBackoff = doubled.compareTo(settings.backoffMax()) > 0 ? settings.backoffMax() : doubled;
        Duration capped = idleBackoff.compareTo(settings.maxIdleSleep()) > 0 ? settings.maxIdleSleep() : idleBackoff;
        return applyJitter(capped);
    }

    Duration applyJitter(Duration base) {
        double fraction = ThreadLocalRandom.current().nextDouble(-1.0, 1.0) * settings.jitterRatio();
        long millis = Math.max(1L, Math.round(base.toMillis() * (1.0 + fraction)));
        return Duration.ofMillis(millis);
    }

    private Duration cappedIdleDelay(Duration delay) {
        try {
            return capToNearestDue(delay, claimer.nearestClaimableDueAt(), clock.instant());
        } catch (RuntimeException lookupFailure) {
            log.warn("nearest-due lookup failed; using uncapped idle delay", lookupFailure);
            return delay;
        }
    }

    static Duration capToNearestDue(Duration delay, Optional<Instant> nearestDueAt, Instant now) {
        if (nearestDueAt.isEmpty() || !nearestDueAt.get().isAfter(now)) {
            return delay;
        }
        Duration untilDue = Duration.between(now, nearestDueAt.get());
        return untilDue.compareTo(delay) < 0 ? untilDue : delay;
    }
}
