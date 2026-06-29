package com.bff.pipeline.utils;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.entity.Task;
import java.time.Clock;
import java.time.Duration;

/**
 * Per-task knob resolution and deadline math. A pure static utility — every method is
 * (task, settings) → value with no state — so it stays out of {@link TaskMachine} and needs no
 * bean. A task's own override wins; otherwise the global {@link PipelineSettings} default applies.
 *
 * <p>{@code pastDeadline} is true once the task has run past a deadline, measured from the current
 * attempt's {@code startedAt}. A retry is a fresh run that resets {@code startedAt} (ADR-016 §6), so the
 * execution-timeout / time-to-live bound each attempt, not the task's whole lifetime.
 */
public final class TaskKnobs {

    private TaskKnobs() {
    }

    public static Duration executionTimeout(Task task, PipelineSettings settings) {
        return task.getExecutionTimeout() != null ? task.getExecutionTimeout() : settings.executionTimeout();
    }

    public static Duration timeToLive(Task task, PipelineSettings settings) {
        return task.getTimeToLive() != null ? task.getTimeToLive() : settings.timeToLive();
    }

    public static Duration pollingInterval(Task task, PipelineSettings settings) {
        return task.getPollingInterval() != null ? task.getPollingInterval() : settings.pollingInterval();
    }

    public static int maxFailCount(Task task, PipelineSettings settings) {
        return task.getMaxFailCount() != null ? task.getMaxFailCount() : settings.maxFailCount();
    }

    public static boolean pastDeadline(Task task, Duration deadline, Clock clock) {
        return task.getStartedAt() != null && !clock.instant().isBefore(task.getStartedAt().plus(deadline));
    }
}
