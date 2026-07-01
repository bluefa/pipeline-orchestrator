package com.bff.pipeline.utils;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.entity.Task;
import java.time.Clock;
import java.time.Duration;

/**
 * 태스크별 파라미터 해석 및 데드라인 계산을 담당하는 순수 정적 유틸리티 클래스이다.
 * 모든 메서드는 (task, settings) → value 형태로 상태를 갖지 않으므로,
 * {@code TaskStateMachine} 외부에 위치하며 빈(bean)으로 등록할 필요가 없다.
 * 태스크 자체의 오버라이드 값이 우선 적용되며, 없을 경우 전역 {@link PipelineSettings} 기본값이 사용된다.
 *
 * <p>{@code isPastDeadline}은 현재 attempt의 {@code startedAt}을 기준으로 태스크가 데드라인을
 * 초과하였는지 판별한다. 재시도는 {@code startedAt}을 초기화하는 새로운 실행이므로(ADR-016 §6),
 * 실행 타임아웃(execution-timeout)과 TTL은 태스크 전체 생명주기가 아닌 각 attempt를 기준으로 적용된다.
 */
public final class TaskSettings {

    private TaskSettings() {
    }

    public static Duration resolveExecutionTimeout(Task task, PipelineSettings settings) {
        return task.getExecutionTimeout() != null ? task.getExecutionTimeout() : settings.executionTimeout();
    }

    public static Duration resolveTimeToLive(Task task, PipelineSettings settings) {
        return task.getTimeToLive() != null ? task.getTimeToLive() : settings.timeToLive();
    }

    public static Duration resolvePollingInterval(Task task, PipelineSettings settings) {
        return task.getPollingInterval() != null ? task.getPollingInterval() : settings.pollingInterval();
    }

    public static int resolveMaxFailCount(Task task, PipelineSettings settings) {
        return task.getMaxFailCount() != null ? task.getMaxFailCount() : settings.maxFailCount();
    }

    public static boolean isPastDeadline(Task task, Duration deadline, Clock clock) {
        return task.getStartedAt() != null && !clock.instant().isBefore(task.getStartedAt().plus(deadline));
    }
}
