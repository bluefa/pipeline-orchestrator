package com.bff.pipeline.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yml의 {@code pipeline.*} 키에서 바인딩되는 전역 태스크 데드라인 기본값 설정이다
 * ({@code @ConfigurationProperties(prefix = "pipeline")}). 태스크는 이 값을 얼마든지 오버라이드할 수 있고,
 * 오버라이드하지 않으면 여기서 정한 값이 쓰인다. (실행 케이던스 — 틱 간격, 호출별 타임아웃, 워커 풀 크기 —
 * 는 ADR-021 러너의 몫이라 여기서 설정하지 않는다.) 모든 필드는 {@code @DefaultValue}로 코드 기본값을 가져,
 * yml/env에서 설정하지 않아도 기본값으로 바인딩된다(배포는 필요한 것만 오버라이드). {@code @Builder}로 명명 생성도 지원한다.
 *
 * {@code startDelay}는 파이프라인 생성 후 첫 Task dispatch까지의 대기다. PipelineInserter가 생성 시
 * {@code nextDueAt = now + startDelay}로 시딩해, claim 술어({@code next_due_at <= now})가 지연 경과 전에는
 * 이 실행을 잡지 않게 한다(sleep 없이 스케줄링으로 지연). 0이면 즉시 시작한다.
 *
 * <p>compact constructor가 값을 검증한다. 미설정은 {@code @DefaultValue}로 채워지므로 실패하지 않지만, 명시한
 * 값이 유효하지 않으면(양수가 아닌 duration, 음수 start-delay, {@code maxFailCount}·{@code maxTerraformPollCallErrors}가
 * 1 미만) 문제가 된 키 이름과 함께 곧바로 시작에 실패한다(fail fast). 이렇게 막아 두면 데드라인 계산
 * ({@code TaskSettingsResolver})이나 terraform 폴 임계 판정에서 나중에 NPE나 조용한 오동작으로 번지지 않는다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline")
public record PipelineSettings(
        @DefaultValue("PT50M") Duration executionTimeout,
        @DefaultValue("PT10M") Duration pollingInterval,
        @DefaultValue("2") int maxFailCount,
        @DefaultValue("10") int maxTerraformPollCallErrors,
        @DefaultValue("PT15S") Duration startDelay) {

    public PipelineSettings {
        requirePositive(executionTimeout, "pipeline.execution-timeout");
        requirePositive(pollingInterval, "pipeline.polling-interval");
        if (maxFailCount < 1) {
            throw new IllegalArgumentException("pipeline.max-fail-count must be >= 1, was " + maxFailCount);
        }
        if (maxTerraformPollCallErrors < 1) {
            // 1 미만이면 첫 폴 전에 모든 terraform job을 즉시 관측 불능으로 확정하는 오동작이 되므로 시작에서 막는다.
            throw new IllegalArgumentException(
                    "pipeline.max-terraform-poll-call-errors must be >= 1, was " + maxTerraformPollCallErrors);
        }
        requireNonNegative(startDelay, "pipeline.start-delay");   // 0이면 지연 없이 즉시 시작(테스트 오버라이드)
    }

    private static void requireNonNegative(Duration value, String property) {
        if (value == null) {
            throw new IllegalArgumentException(property + " must be set");
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(property + " must not be negative, was " + value);
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
