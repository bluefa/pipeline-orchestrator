package com.bff.pipeline.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code pipeline.execution.*} 키로부터 바인딩되는 ADR-021 실행(런타임) 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline.execution")}). 도메인 데드라인 기본값은
 * {@link PipelineSettings}가, 실행 케이던스/동시성/리스/소프트캡은 이 record가 소유한다.
 *
 * <p>compact constructor에서 fail-fast 검증한다: 정수 캡은 1 이상, 모든 Duration은 양수,
 * {@code jitterRatio}는 0~1, 그리고 ADR-021 Decision 5의 하드 제약
 * {@code leaseDuration > apiCallTimeout}을 강제한다(어기면 문제 키 이름과 함께 시작 실패).
 * 이는 리스가 호출 타임아웃보다 짧아 정상 운영 중 tx2가 만료 리스로 no-op되는 병리를 막는다.
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
        requireAtLeastOne(workerPerPod, "pipeline.execution.worker-per-pod");
        requireAtLeastOne(runningPipelineCap, "pipeline.execution.running-pipeline-cap");
        requireAtLeastOne(slotCap, "pipeline.execution.slot-cap");
        requirePositive(leaseDuration, "pipeline.execution.lease-duration");
        requirePositive(apiCallTimeout, "pipeline.execution.api-call-timeout");
        requirePositive(slotRetry, "pipeline.execution.slot-retry");
        requirePositive(pollInterval, "pipeline.execution.poll-interval");
        requirePositive(maxIdleSleep, "pipeline.execution.max-idle-sleep");
        requirePositive(backoffBase, "pipeline.execution.backoff-base");
        requirePositive(backoffMax, "pipeline.execution.backoff-max");
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException(
                    "pipeline.execution.jitter-ratio must be within [0.0, 1.0], was " + jitterRatio);
        }
        if (leaseDuration.compareTo(apiCallTimeout) <= 0) {
            throw new IllegalArgumentException("pipeline.execution.lease-duration (" + leaseDuration
                    + ") must exceed pipeline.execution.api-call-timeout (" + apiCallTimeout
                    + ") per ADR-021 Decision 5");
        }
    }

    private static void requireAtLeastOne(int value, String property) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be >= 1, was " + value);
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
