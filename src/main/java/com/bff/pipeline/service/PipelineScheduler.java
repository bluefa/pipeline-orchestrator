package com.bff.pipeline.service;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.client.InfraManagerClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * ADR-021 §278-283 자기-재조정 적응형 클레임-폴 루프 스케줄러이다.
 *
 * <p>단일 스레드 데몬 {@link ScheduledExecutorService}가 {@code runSweep}을 반복 스케줄하며,
 * 매 스윕마다 {@code workerPerPod}개의 드레인 태스크를 {@code pipelineWorkerPool}에 병렬 제출하고
 * 모든 드레인 완료를 기다린다. 빈 스윕 후에는 즉시 재시도하지 않고 적응형 백오프를 적용한다:
 * 유휴 대기 시간이 {@code backoffBase}에서 시작해 연속된 빈 스윕마다 두 배씩 증가하며
 * {@code backoffMax}로 상한이 제한되고, {@code maxIdleSleep}으로 실제 슬립 시간이 추가 제한된다.
 * 작업이 발견되면 대기 시간을 {@code pollInterval} 케이던스로 초기화한다.
 * 파드 간 동기화된 웨이크업을 방지하기 위해 ±{@code jitterRatio}로 지터를 적용한다.
 * 멀티-파드 안전성은 DB 클레임({@code FOR UPDATE SKIP LOCKED} + 리스 스탬프)에만 의존한다
 * (ADR-021 Decision 1/7).
 *
 * <p><b>nextDueAt-인식 유휴 슬립 (ADR-021 §280):</b> 유휴 스윕 후 가장 가까운 due 파이프라인 시각을
 * DB에서 조회하여({@link PipelineClaimer#nearestClaimableDueAt()}) 유휴 슬립을
 * {@code min(backoff, nextDueAt − now)}로 단축한다. 이로써 백오프 케이던스가 긴 상황에서도
 * due 파이프라인이 생기는 즉시 깨어나며, 결과가 없거나 이미 만료된 경우에는 원래 백오프를 유지한다.
 *
 * <p><b>클레임 단계 실패:</b> {@link PipelineClaimer#claimOneDue()}에서 발생한
 * {@link RuntimeException}은 드레인을 즉시 종료한다 — DB 장애 시 due 행을 지연 없이 재시도하는
 * 해머 루프를 방지하고, 적응형 백오프가 재시도 속도를 제어한다.
 *
 * <p><b>처리 단계 실패(파이프라인별 격리):</b> {@link PipelineWorker#process}에서 발생한
 * {@link RuntimeException}은 WARN 로그 후 건너뛰고 드레인을 계속한다 — 파이프라인이 리스를 유지하며
 * 만료 시 자동 회수된다. 단일 실패가 스윕 전체를 중단시켜 다른 파이프라인을 굶기지 않는다.
 *
 * <p><b>{@link InfraManagerClient.CallInterruptedException}:</b> JVM 종료 신호로 간주하여
 * 스레드 인터럽트를 복원하고 스윕 전체를 즉시 중단한다 — 재스케줄하지 않는다.
 */
@Component
public class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    private final PipelineWorker worker;
    private final PipelineClaimer claimer;
    private final ExecutorService pool;
    private final ExecutionSettings settings;
    private final Duration initialDelay;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private Duration idleBackoff;

    public PipelineScheduler(
            PipelineWorker worker,
            PipelineClaimer claimer,
            @Qualifier("pipelineWorkerPool") ExecutorService pool,
            ExecutionSettings settings,
            @Value("${pipeline.execution.scheduler-initial-delay:PT5S}") Duration initialDelay,
            Clock clock) {
        this.worker = worker;
        this.claimer = claimer;
        this.pool = pool;
        this.settings = settings;
        this.initialDelay = initialDelay;
        this.clock = clock;
        this.idleBackoff = settings.backoffBase();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pipeline-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    public void start() {
        scheduler.schedule(this::runSweep, initialDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * 자기-재조정 스윕 루프의 단일 실행 단위이다. 예상치 못한 예외가 루프를 종료시키지 않도록
     * try/finally에서 항상 다음 실행을 재스케줄한다. 인터럽트(JVM 종료 신호)가 감지되면 재스케줄하지 않고
     * 루프를 종료한다.
     *
     * <p><b>nearest-due 조회 내결함성:</b> 유휴 스윕 후 nearest-due DB 조회는 best-effort이다.
     * 조회가 실패해도 {@link #cappedIdleDelay(Duration)}가 예외를 흡수하고 비제한 적응형 대기 시간으로
     * 폴백하므로, DB 일시 장애 중에도 {@code scheduler.schedule(...)}이 반드시 실행되어
     * 자기-재조정 루프가 영구 중단되지 않는다.
     */
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

    /**
     * 유휴 슬립을 nearest-due 시각으로 단축하되, DB 조회 실패 시 원래 대기 시간을 그대로 반환한다.
     *
     * <p>nearest-due 조회({@link PipelineClaimer#nearestClaimableDueAt()})는 best-effort이다:
     * {@link RuntimeException}이 발생하면 WARN 로그 후 입력 {@code delay}를 그대로 반환하여
     * DB 일시 장애 중에도 자기-재조정 루프가 계속 동작할 수 있도록 한다.
     * {@link #capToNearestDue(Duration, java.util.Optional, java.time.Instant)}는 순수 함수로
     * 변경하지 않는다 — DB 호출만 이 메서드에서 보호된다.
     */
    Duration cappedIdleDelay(Duration delay) {
        try {
            return capToNearestDue(delay, claimer.nearestClaimableDueAt(), clock.instant());
        } catch (RuntimeException runtimeException) {
            log.warn("nearest-due lookup failed; falling back to the uncapped idle delay", runtimeException);
            return delay;
        }
    }

    /**
     * {@code workerPerPod}개의 드레인 태스크를 병렬 제출하고 모든 완료를 기다린다.
     * 하나라도 파이프라인을 처리한 드레인이 있으면 {@code true}를 반환한다.
     * 드레인에서 {@link InfraManagerClient.CallInterruptedException}이 발생하면
     * 나머지 미완료 퓨처를 모두 취소하고, 인터럽트를 복원한 뒤 {@link InterruptedException}을 던져
     * {@code runSweep}이 재스케줄하지 않도록 한다.
     */
    boolean sweepOnce() throws InterruptedException {
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < settings.workerPerPod(); index++) {
            futures.add(pool.submit(this::drain));
        }
        boolean anyFound = false;
        for (Future<Boolean> future : futures) {
            try {
                if (Boolean.TRUE.equals(future.get())) {
                    anyFound = true;
                }
            } catch (InterruptedException interruptedException) {
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw interruptedException;
            } catch (ExecutionException executionException) {
                if (executionException.getCause() instanceof InfraManagerClient.CallInterruptedException) {
                    futures.forEach(f -> f.cancel(true));
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("shutdown interrupt during sweep");
                }
                if (executionException.getCause() instanceof Error error) { throw error; }
                log.warn("worker drain failed", executionException.getCause());
            }
        }
        return anyFound;
    }

    /**
     * 작업이 없을 때까지 한 파이프라인씩 처리한다. 하나 이상의 파이프라인을 처리했으면 {@code true}를 반환한다.
     *
     * <p>클레임 단계({@link PipelineClaimer#claimOneDue()})에서 {@link RuntimeException}이 발생하면
     * WARN 로그 후 드레인을 즉시 종료한다 — DB 장애 시 same due 행을 지연 없이 재시도하는 해머 루프를
     * 방지하며, 적응형 백오프가 다음 재시도 속도를 제어한다.
     *
     * <p>처리 단계({@link PipelineWorker#process})에서 {@link RuntimeException}이 발생하면
     * WARN 로그 후 건너뛰고 계속한다 — 파이프라인이 리스를 유지하며 만료 시 자동 회수된다(파이프라인별 격리).
     *
     * <p>{@link InfraManagerClient.CallInterruptedException}은 두 단계 모두에서 종료 신호이므로
     * 인터럽트를 복원하고 즉시 re-throw하여 스윕 전체를 중단시킨다.
     */
    boolean drain() {
        boolean anyFound = false;
        while (true) {
            if (Thread.currentThread().isInterrupted()) { throw new InfraManagerClient.CallInterruptedException(); }
            Optional<PipelineClaimer.Claim> claimed;
            try {
                claimed = claimer.claimOneDue();
            } catch (InfraManagerClient.CallInterruptedException callInterruptedException) {
                Thread.currentThread().interrupt();
                throw callInterruptedException;
            } catch (RuntimeException runtimeException) {
                log.warn("claim failed — ending drain; next sweep retries with backoff", runtimeException);
                return anyFound;
            }
            if (claimed.isEmpty()) return anyFound;
            try {
                worker.process(claimed.get());
                anyFound = true;
            } catch (InfraManagerClient.CallInterruptedException callInterruptedException) {
                Thread.currentThread().interrupt();
                throw callInterruptedException;
            } catch (RuntimeException runtimeException) {
                log.warn("pipeline advance failed — skipping (the pipeline keeps its lease and is reclaimed on expiry)", runtimeException);
                anyFound = true;
            }
        }
    }

    /**
     * 작업 발견 여부에 따라 다음 스윕까지의 대기 시간을 계산한다(ADR-021 §278-283).
     * 작업이 발견되면 {@code pollInterval} 케이던스로 초기화한다.
     * 빈 스윕이면 {@code idleBackoff}를 두 배로 늘려({@code backoffMax} 제한) 지터를 적용하고,
     * {@code maxIdleSleep}으로 추가 상한을 제한한다.
     */
    Duration nextDelay(boolean workFound) {
        if (workFound) {
            idleBackoff = settings.backoffBase();
            return settings.pollInterval();
        }
        Duration doubled = idleBackoff.multipliedBy(2);
        idleBackoff = doubled.compareTo(settings.backoffMax()) <= 0 ? doubled : settings.backoffMax();
        Duration capped = idleBackoff.compareTo(settings.maxIdleSleep()) <= 0 ? idleBackoff : settings.maxIdleSleep();
        return applyJitter(capped);
    }

    /**
     * 지정된 Duration에 ±{@code jitterRatio} 범위의 무작위 지터를 적용한다.
     * 결과가 1ms 미만이 되지 않도록 하한을 1ms로 고정한다.
     */
    Duration applyJitter(Duration duration) {
        double jitterFraction = ThreadLocalRandom.current().nextDouble(-1.0, 1.0) * settings.jitterRatio();
        long baseMillis = duration.toMillis();
        long jitteredMillis = Math.max(1L, Math.round(baseMillis * (1.0 + jitterFraction)));
        return Duration.ofMillis(jitteredMillis);
    }

    /**
     * ADR-021 §280 nextDueAt-인식 유휴 슬립 단축 헬퍼이다. 순수 함수(pure)이므로 단위 테스트에서
     * 직접 호출할 수 있다.
     *
     * <p>{@code nearestDueAt}이 비어 있거나 이미 만료된({@code now} 이전) 경우에는 {@code delay}를
     * 그대로 반환한다. {@code nearestDueAt}이 {@code now} 이후이면 {@code delay}와
     * {@code nearestDueAt − now} 중 작은 값을 반환한다.
     */
    Duration capToNearestDue(Duration delay, Optional<Instant> nearestDueAt, Instant now) {
        if (nearestDueAt.isEmpty() || !nearestDueAt.get().isAfter(now)) return delay;
        Duration untilDue = Duration.between(now, nearestDueAt.get());
        return delay.compareTo(untilDue) <= 0 ? delay : untilDue;
    }
}
