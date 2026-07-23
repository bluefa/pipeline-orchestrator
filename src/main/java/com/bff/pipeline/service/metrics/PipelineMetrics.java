package com.bff.pipeline.service.metrics;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.notify.TerminalNotifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 운영 지표 계측의 중심 컴포넌트다. 실행 엔진과 알림 루프에서 일어나는 일을 숫자로 바꿔
 * 모니터링 시스템(Prometheus 형식)에 내보낸다. 지표 이름과 태그는 전부 이 클래스의 상수로만
 * 정의해서, 호출하는 쪽 코드에 지표 문자열이 흩어지지 않게 한다.
 *
 * 게이지(현재 상태 값) 네 개는 생성 시점에 등록되고, 수집기가 값을 긁어 갈 때마다 저장소 질의로
 * 그 순간의 값을 계산한다.
 * - pipeline.due.lag.seconds: 지금 집어갈 수 있는 파이프라인 중 가장 오래 늦은 것의 지연(초).
 *   스케줄러 정지, 워커 부족, DB 정체가 전부 이 값의 증가로 나타나는 진행성의 종합 신호다.
 * - terraform.slot.occupied: 테라폼 동시 실행 슬롯을 차지하고 있는 작업 수.
 * - notify.giveup.count: 자동 재시도가 멈춰 사람 개입을 기다리는 종단 알림 수. 0보다 크면 즉시 경보 대상이다.
 * - notify.pending.age.seconds: 아직 나가지 못한(지금 보낼 수 있는) 종단 알림 중 가장 오래된 것의
 *   나이(초). 알림 기능이 꺼진 배포에서는 밀린 알림이 정상 상태이므로 켠 배포에서만 등록한다.
 *
 * 카운터(누적 횟수)는 사건이 일어난 자리에서 메서드 호출로 올린다. 종단 카운터(pipeline.terminal)만은
 * 트랜잭션이 커밋된 뒤에 1 올린다 — 롤백된 종단 전이까지 세면 성공률 통계가 실제와 어긋나기 때문이다.
 */
@Component
public class PipelineMetrics {

    public static final String PIPELINE_TERMINAL = "pipeline.terminal";
    static final String TASK_ATTEMPT_FAILURE = "task.attempt.failure";
    static final String STALE_WRITE_BACK = "pipeline.writeback.stale";
    static final String PIPELINE_DUE_LAG = "pipeline.due.lag.seconds";
    static final String TERRAFORM_SLOT_OCCUPIED = "terraform.slot.occupied";
    static final String NOTIFY_GIVE_UP = "notify.giveup.count";
    static final String NOTIFY_PENDING_AGE = "notify.pending.age.seconds";

    public static final String STATUS_TAG = "status";
    public static final String TYPE_TAG = "type";
    static final String ERROR_CODE_TAG = "error_code";
    /** type이 비어 있는 옛 행(지금의 enum이 해석하지 못하는 값)을 위한 태그 값. */
    static final String TYPE_UNKNOWN = "UNKNOWN";

    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public PipelineMetrics(MeterRegistry meterRegistry, PipelineRepository pipelineRepository,
            TaskRepository taskRepository, NotifySettings notifySettings, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        Gauge.builder(PIPELINE_DUE_LAG, () -> dueLagSeconds(pipelineRepository)).register(meterRegistry);
        Gauge.builder(TERRAFORM_SLOT_OCCUPIED,
                () -> taskRepository.countByConsumesTerraformSlotIsTrueAndStatus(TaskStatus.IN_PROGRESS))
                .register(meterRegistry);
        Gauge.builder(NOTIFY_GIVE_UP, () -> pipelineRepository.countGivenUp(TerminalNotifier.MAX_ATTEMPTS))
                .register(meterRegistry);
        if (notifySettings.enabled()) {
            Gauge.builder(NOTIFY_PENDING_AGE, () -> notifyPendingAgeSeconds(pipelineRepository, notifySettings))
                    .register(meterRegistry);
        }
    }

    /**
     * 파이프라인이 끝났음(DONE, FAILED, CANCELLED)을 센다. 종단 전이를 쓰는 트랜잭션 안에서 부르면
     * 커밋이 성공한 뒤에만 1 올라간다 — 종단 경로마다 정확히 한 번씩 부르는 것이 호출하는 쪽의 계약이다.
     */
    public void pipelineTerminalized(PipelineStatus status, PipelineType type) {
        Counter terminalCounter = Counter.builder(PIPELINE_TERMINAL)
                .tag(STATUS_TAG, status.name())
                .tag(TYPE_TAG, type == null ? TYPE_UNKNOWN : type.name())
                .register(meterRegistry);
        afterCommitOrNow(terminalCounter::increment);
    }

    /**
     * 실패한 시도를 원인 코드별로 센다. 최종 실패가 아니라 시도 단위다 — 재시도로 회복될 실패까지
     * 포함해야 외부 의존성 이상(CALL_TIMEOUT, CHECK_ERROR 급증)을 조기에 볼 수 있다.
     */
    public void taskAttemptFailed(ErrorCode errorCode) {
        meterRegistry.counter(TASK_ATTEMPT_FAILURE, ERROR_CODE_TAG, errorCode.name()).increment();
    }

    /**
     * 소유권(claim 토큰)이 이미 넘어간 뒤 도착해서 버려진 write-back을 센다. 데이터는 안전하게
     * 지켜지지만, 이 값이 늘고 있다는 것은 점유 시간(lease)이 실제 작업 시간을 못 덮어
     * 외부 호출이 중복 실행되고 있다는 뜻이다 — 성공률 지표에는 절대 나타나지 않는 낭비 신호다.
     */
    public void staleWriteBackDiscarded() {
        meterRegistry.counter(STALE_WRITE_BACK).increment();
    }

    /** 트랜잭션 안이면 커밋 성공 뒤에, 밖이면 즉시 실행한다. */
    private void afterCommitOrNow(Runnable increment) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            increment.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                increment.run();
            }
        });
    }

    private double dueLagSeconds(PipelineRepository pipelineRepository) {
        Instant now = clock.instant();
        return pipelineRepository.findNearestClaimableDueAt(now)
                .filter(nearestDueAt -> nearestDueAt.isBefore(now))
                .map(nearestDueAt -> (double) Duration.between(nearestDueAt, now).toSeconds())
                .orElse(0.0);
    }

    private double notifyPendingAgeSeconds(PipelineRepository pipelineRepository, NotifySettings notifySettings) {
        Instant now = clock.instant();
        return pipelineRepository.findOldestPendingNotifiableFinishedAt(
                        now, TerminalNotifier.MAX_ATTEMPTS, notifySettings.enabledAfter())
                .map(finishedAt -> (double) Duration.between(finishedAt, now).toSeconds())
                .orElse(0.0);
    }
}
