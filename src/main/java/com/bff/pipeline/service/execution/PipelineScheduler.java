package com.bff.pipeline.service.execution;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.client.InfraManagerClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.bff.pipeline.exception.CallInterruptedException;

/**
 * 스스로 다음 실행을 예약하는 적응형 claim-pull 루프이다(Decision 1/7). 단일 데몬
 * {@link ScheduledExecutorService}가 sweep을 돌리고, 각 sweep은 {@code workerPerPod}개의 drain 작업을
 * 워커 풀에 던져 동시에 claim하고 처리한다. 다음 sweep 예약 간격은 이번 sweep 결과에 따라 달라진다 —
 * 일이 있었으면 {@code pollInterval}, 빈 sweep이었으면 geometric jitter backoff로 잡고, idle sleep은
 * nearest-due 시각까지로 한 번 더 좁힌다.
 *
 * <p><b>빈 claim ≠ backlog empty</b>: SKIP LOCKED가 동료가 잡은 행을 건너뛰므로 빈 결과를 유휴로 보지 않고,
 * 폴링 케이던스만 backoff로 늦춘다. <b>회복력</b>: claim 실패는 DB 난타를 피하려고 drain을 끝내고, 처리
 * 실패는 pipeline 단위로 격리되며(그 claim은 lease가 만료되면 다시 잡힌다), nearest-due 조회 실패는
 * uncapped fallback으로 넘어가 루프를 살린다. 재예약을 멈추는 건 오직 인터럽트(JVM 종료)뿐이다.
 */
@Slf4j
@Component
public class PipelineScheduler {

    private final PipelineWorker pipelineWorker;
    private final PipelineClaimer pipelineClaimer;
    private final ExecutorService pipelineWorkerPool;
    private final ExecutionSettings executionSettings;
    private final Clock clock;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pipeline-scheduler");
                thread.setDaemon(true);
                return thread;
            });
    private Duration idleBackoff;

    public PipelineScheduler(PipelineWorker pipelineWorker, PipelineClaimer pipelineClaimer,
            @Qualifier("pipelineWorkerPool") ExecutorService pipelineWorkerPool, ExecutionSettings executionSettings,
            Clock clock) {
        this.pipelineWorker = pipelineWorker;
        this.pipelineClaimer = pipelineClaimer;
        this.pipelineWorkerPool = pipelineWorkerPool;
        this.executionSettings = executionSettings;
        this.clock = clock;
        this.idleBackoff = executionSettings.backoffBase();
    }

    @PostConstruct
    void start() {
        scheduler.schedule(this::runSweep, executionSettings.schedulerInitialDelay().toMillis(), TimeUnit.MILLISECONDS);
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
        for (int i = 0; i < executionSettings.workerPerPod(); i++) {
            futures.add(pipelineWorkerPool.submit((Callable<Boolean>) this::drain));
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
                if (cause instanceof CallInterruptedException) {
                    futures.forEach(f -> f.cancel(true));
                    throw new InterruptedException("pipelineWorker interrupted");
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
                throw new CallInterruptedException();
            }
            Optional<Claim> claim;
            try {
                claim = pipelineClaimer.claimOneDue();
            } catch (CallInterruptedException interrupted) {
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
                pipelineWorker.process(claim.get());
            } catch (CallInterruptedException interrupted) {
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
            idleBackoff = executionSettings.backoffBase();
            return executionSettings.pollInterval();
        }
        idleBackoff = min(idleBackoff.multipliedBy(2), executionSettings.backoffMax());
        return applyJitter(min(idleBackoff, executionSettings.maxIdleSleep()));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    Duration applyJitter(Duration base) {
        double fraction = ThreadLocalRandom.current().nextDouble(-1.0, 1.0) * executionSettings.jitterRatio();
        long millis = Math.max(1L, Math.round(base.toMillis() * (1.0 + fraction)));
        return Duration.ofMillis(millis);
    }

    private Duration cappedIdleDelay(Duration delay) {
        try {
            return capToNearestDue(delay, pipelineClaimer.nearestClaimableDueAt(), clock.instant());
        } catch (RuntimeException lookupFailure) {
            log.warn("nearest-due lookup failed; using uncapped idle delay", lookupFailure);
            return delay;
        }
    }

    static Duration capToNearestDue(Duration delay, Optional<Instant> nearestDueAt, Instant now) {
        return nearestDueAt
                .filter(due -> due.isAfter(now))                        // 미래의 due만 상한 후보
                .map(due -> min(Duration.between(now, due), delay))     // 그 due까지로 idle sleep을 좁힘
                .orElse(delay);                                        // due 없거나 이미 지남 → 그대로
    }
}
