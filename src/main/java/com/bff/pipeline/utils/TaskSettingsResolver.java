package com.bff.pipeline.utils;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.entity.Task;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 태스크별 파라미터 해석과 데드라인 계산을 맡는 순수 정적 유틸리티 클래스다. 모든 메서드가
 * (task, settings) → value 꼴로 상태를 갖지 않으므로 {@code TaskStateMachine} 바깥에 두고 빈으로 등록하지
 * 않는다. 태스크 자체의 오버라이드 값이 먼저고, 없으면 전역 {@link PipelineSettings} 기본값을 쓴다.
 *
 * <p>{@code isPastDeadline}은 현재 attempt의 {@code startedAt}을 기준으로 데드라인을 넘겼는지 판별한다.
 * 재시도는 {@code startedAt}을 새로 찍는 별개의 실행이므로(ADR-016 §6), 실행 타임아웃(execution-timeout)은
 * 태스크 전체 생명주기가 아니라 attempt마다 따로 적용된다(TERRAFORM_JOB 전용).
 */
public final class TaskSettingsResolver {

    private TaskSettingsResolver() {
    }

    public static Duration resolveExecutionTimeout(Task task, PipelineSettings settings) {
        return task.getExecutionTimeout() != null ? task.getExecutionTimeout() : settings.executionTimeout();
    }

    public static Duration resolvePollingInterval(Task task, PipelineSettings settings) {
        return task.getPollingInterval() != null ? task.getPollingInterval() : settings.pollingInterval();
    }

    public static int resolveMaxFailCount(Task task, PipelineSettings settings) {
        return task.getMaxFailCount() != null ? task.getMaxFailCount() : settings.maxFailCount();
    }

    public static boolean isPastDeadline(Task task, Duration deadline, Clock clock) {
        if (task.getStartedAt() == null) {
            return false;   // 아직 시작 전 → 데드라인 없음
        }
        Instant deadlineAt = task.getStartedAt().plus(deadline);
        return !clock.instant().isBefore(deadlineAt);   // now ≥ deadlineAt (데드라인 도달/경과)
    }
}
