package com.bff.pipeline.service;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.client.InfraManagerClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-021 Decision 1 고정 케이던스 스윕 스케줄러이다.
 *
 * <p>매 폴링 간격마다 {@code workerPerPod}개의 병렬 드레인(drain) 태스크를 {@code pipelineWorkerPool}에
 * 제출하고, 모든 드레인이 완료될 때까지 대기한다(고정 지연 pacing). 각 드레인은 클레임 가능한
 * 파이프라인이 없을 때까지 {@link PipelineWorker#pollOnce()}를 반복 호출하여 파이프라인을 한 건씩 처리한다.
 *
 * <p><b>파이프라인별 격리(per-pipeline isolation):</b> 한 파이프라인 처리 중 발생한
 * {@link RuntimeException}은 WARN 로그 후 건너뛰고 드레인을 계속한다 — 단일 실패가 스윕 전체를
 * 중단시켜 다른 파이프라인을 굶기지 않는다. 실패한 파이프라인은 tx1(claim)이 이미 커밋되어
 * 리스(lease)가 찍혀 있으므로 다음 클레임 스캔에 나타나지 않으며, 리스 만료 후 재클레임된다
 * (ADR-021 Decision 5 크래시 복구 메커니즘과 동일).
 *
 * <p><b>{@link InfraManagerClient.CallInterruptedException}:</b> JVM 종료 신호로 간주하여
 * 스레드 인터럽트를 복원하고 드레인을 즉시 중단한다.
 *
 * <p><b>멀티-파드 안전성:</b> DB 클레임({@code FOR UPDATE SKIP LOCKED} + 리스 스탬프)이 유일한
 * 조정 프리미티브이다 — 파드 수나 프로세스 카운트는 정확성(correctness)에 영향을 주지 않는다
 * (ADR-021 Decision 1/7). 고정 지연 + {@code next_due_at} 정렬이 유휴 페이싱을 제공하며,
 * 백오프/지터 운영 파라미터가 DB 부하 제어의 조정 표면(tuning surface)이다.
 */
@Component
public class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    private final PipelineWorker worker;
    private final ExecutorService pool;
    private final ExecutionSettings settings;

    public PipelineScheduler(
            PipelineWorker worker,
            @Qualifier("pipelineWorkerPool") ExecutorService pool,
            ExecutionSettings settings) {
        this.worker = worker;
        this.pool = pool;
        this.settings = settings;
    }

    /**
     * 고정 지연 스윕: {@code workerPerPod}개의 드레인을 병렬 제출하고 전체 완료를 기다린다.
     * 고정 지연(fixed delay)은 이 메서드가 반환된 시점부터 시작되므로 드레인 대기가 자연스러운 페이싱을 제공한다.
     */
    @Scheduled(
            fixedDelayString = "${pipeline.execution.poll-interval}",
            initialDelayString = "${pipeline.execution.scheduler-initial-delay:PT5S}")
    public void sweep() {
        List<Future<?>> drains = new ArrayList<>();
        for (int index = 0; index < settings.workerPerPod(); index++) {
            drains.add(pool.submit(this::drain));
        }
        for (Future<?> drain : drains) {
            try {
                drain.get();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException executionException) {
                log.warn("worker drain failed", executionException.getCause());
            }
        }
    }

    /**
     * 작업이 없을 때까지 한 파이프라인씩 처리한다.
     * 파이프라인별 격리: 한 건의 {@link RuntimeException} 실패는 WARN 로그 후 건너뛰고 계속한다.
     * {@link InfraManagerClient.CallInterruptedException}은 종료 신호이므로 인터럽트를 복원하고 즉시 반환한다.
     */
    private void drain() {
        boolean workFound = true;
        while (workFound) {
            try {
                workFound = worker.pollOnce().isPresent();
            } catch (InfraManagerClient.CallInterruptedException callInterruptedException) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException runtimeException) {
                log.warn("pipeline advance failed — skipping this pipeline for the sweep", runtimeException);
                // 실패한 파이프라인은 tx1(claim)이 커밋되어 리스가 찍혀 있으므로 다음 클레임 스캔에서 제외된다.
                // 드레인은 다음 클레임 가능한 파이프라인으로 계속 진행한다.
            }
        }
    }
}
