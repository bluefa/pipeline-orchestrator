package com.bff.pipeline;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code pipeline.*} 키로부터 바인딩되는 전역 태스크 데드라인 기본값 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline")} 적용). 태스크는 이 값들 중 어느 것이든
 * 오버라이드할 수 있으며, 오버라이드하지 않은 경우 여기서 정의한 값이 적용된다.
 * (실행 케이던스 — 틱 간격, 호출별 타임아웃, 워커 풀 크기 — 는 ADR-021 러너에 속하며 여기서 설정하지 않는다.)
 * {@code @Builder}를 통한 명명 생성도 지원한다.
 *
 * <p>compact constructor에서 유효성을 검사한다: {@code pipeline.*} 키가 누락되거나 값이 양수가 아니면
 * 문제가 있는 키 이름과 함께 즉시 시작 실패(fail fast)가 발생한다. 또한 {@code maxFailCount}가
 * 1 미만인 경우에도 시작 시 예외가 발생한다. 이는 데드라인 계산({@code TaskSettings})에서 나중에
 * NPE나 조용한 오동작으로 나타나는 것을 방지한다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline")
public record PipelineSettings(
        Duration executionTimeout,
        Duration timeToLive,
        Duration pollingInterval,
        int maxFailCount) {

    public PipelineSettings {
        requirePositive(executionTimeout, "pipeline.execution-timeout");
        requirePositive(timeToLive, "pipeline.time-to-live");
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
