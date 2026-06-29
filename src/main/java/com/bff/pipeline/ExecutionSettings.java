package com.bff.pipeline;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code pipeline.execution.*} 키로부터 바인딩되는 ADR-021 실행 모델 운영 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline.execution")} 적용).
 *
 * <p>이 설정은 ADR-016 도메인 데드라인({@link PipelineSettings})과 별개로 실행 케이던스와
 * 동시성을 제어한다: 워커 스레드 수, 클레임 리스 윈도우, InfraManager 호출 타임아웃,
 * 동시 실행 파이프라인/TF-슬롯 소프트 캡, 폴링 간격, 유휴 슬립 및 백오프/지터 파라미터.
 *
 * <p>ADR-021 Decision 5 하드 제약: {@code leaseDuration > apiCallTimeout}. 클레임된 파이프라인은
 * 워커 스레드 풀 큐 대기 + 외부 호출 시간 + 스케줄링 여유를 모두 포함한 전체 경과 시간 동안 리스를
 * 유지해야 하므로, 리스 기간은 최대 단일 호출 타임아웃보다 반드시 커야 한다.
 *
 * <p>compact constructor에서 유효성을 검사한다: {@code pipeline.execution.*} 키가 누락되거나
 * 값이 양수가 아니면 문제가 있는 키 이름과 함께 즉시 시작 실패(fail fast)가 발생한다.
 * {@code @Builder}를 통한 명명 생성도 지원한다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline.execution")
public record ExecutionSettings(
        int workerPerPod,
        Duration leaseDuration,
        Duration apiCallTimeout,
        int runningPipelineCap,
        int slotCap,
        Duration slotRetry,
        Duration pollInterval,
        Duration maxIdleSleep,
        Duration backoffBase,
        Duration backoffMax,
        double jitterRatio) {

    public ExecutionSettings {
        if (workerPerPod < 1) {
            throw new IllegalArgumentException(
                    "pipeline.execution.worker-per-pod must be >= 1, was " + workerPerPod);
        }
        requirePositive(leaseDuration, "pipeline.execution.lease-duration");
        requirePositive(apiCallTimeout, "pipeline.execution.api-call-timeout");
        if (runningPipelineCap < 1) {
            throw new IllegalArgumentException(
                    "pipeline.execution.running-pipeline-cap must be >= 1, was " + runningPipelineCap);
        }
        if (slotCap < 1) {
            throw new IllegalArgumentException("pipeline.execution.slot-cap must be >= 1, was " + slotCap);
        }
        requirePositive(slotRetry, "pipeline.execution.slot-retry");
        requirePositive(pollInterval, "pipeline.execution.poll-interval");
        requirePositive(maxIdleSleep, "pipeline.execution.max-idle-sleep");
        requirePositive(backoffBase, "pipeline.execution.backoff-base");
        requirePositive(backoffMax, "pipeline.execution.backoff-max");
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException(
                    "pipeline.execution.jitter-ratio must be >= 0 and <= 1, was " + jitterRatio);
        }
        if (leaseDuration.compareTo(apiCallTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "pipeline.execution.lease-duration must be strictly greater than"
                            + " pipeline.execution.api-call-timeout (ADR-021 Decision 5):"
                            + " leaseDuration=" + leaseDuration + ", apiCallTimeout=" + apiCallTimeout);
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null) {
            throw new IllegalArgumentException(property + " must be set");
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + value);
        }
    }
}
