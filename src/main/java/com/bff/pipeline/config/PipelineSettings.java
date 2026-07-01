package com.bff.pipeline.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code pipeline.*} 키에서 바인딩되는 전역 태스크 데드라인 기본값 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline")}). 태스크는 이 값을 얼마든지 오버라이드할 수 있고,
 * 오버라이드하지 않으면 여기서 정한 값이 쓰인다. (실행 케이던스 — 틱 간격, 호출별 타임아웃, 워커 풀 크기 —
 * 는 ADR-021 러너의 몫이라 여기서 설정하지 않는다.) {@code @Builder}로 명명 생성도 지원한다.
 *
 * <p>compact constructor가 값을 검증한다. {@code pipeline.*} 키가 빠졌거나 값이 양수가 아니면 문제가 된
 * 키 이름과 함께 곧바로 시작에 실패한다(fail fast). {@code maxFailCount}가 1 미만이어도 마찬가지로 시작 시
 * 예외가 난다. 이렇게 막아 두면 데드라인 계산({@code TaskSettingsResolver})에서 나중에 NPE나 조용한 오동작으로
 * 번지지 않는다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline")
public record PipelineSettings(
        Duration executionTimeout,
        Duration pollingInterval,
        int maxFailCount) {

    public PipelineSettings {
        requirePositive(executionTimeout, "pipeline.execution-timeout");
        requirePositive(pollingInterval, "pipeline.polling-interval");
        if (maxFailCount < 1) {
            throw new IllegalArgumentException("pipeline.max-fail-count must be >= 1, was " + maxFailCount);
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
