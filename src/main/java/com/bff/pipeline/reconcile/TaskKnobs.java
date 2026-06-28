package com.bff.pipeline.reconcile;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.domain.Task;
import java.time.Clock;
import java.time.Duration;

/**
 * Per-task knob resolution and deadline math. A pure static utility — every method is
 * (task, settings) → value with no state — so it stays out of {@link TaskMachine} and needs no
 * bean. A task's own override wins; otherwise the global {@link PipelineSettings} default applies.
 */
final class TaskKnobs {

    private TaskKnobs() {
    }

    static Duration executionTimeout(Task task, PipelineSettings settings) {
        return task.getExecutionTimeout() != null ? task.getExecutionTimeout() : settings.executionTimeout();
    }

    static Duration ttl(Task task, PipelineSettings settings) {
        return task.getTtl() != null ? task.getTtl() : settings.ttl();
    }

    static Duration pollingInterval(Task task, PipelineSettings settings) {
        return task.getPollingInterval() != null ? task.getPollingInterval() : settings.pollingInterval();
    }

    static int maxFailCount(Task task, PipelineSettings settings) {
        return task.getMaxFailCount() != null ? task.getMaxFailCount() : settings.maxFailCount();
    }

    /** True once the task has run past {@code deadline} measured from {@code startedAt}. */
    static boolean pastDeadline(Task task, Duration deadline, Clock clock) {
        return task.getStartedAt() != null && !clock.instant().isBefore(task.getStartedAt().plus(deadline));
    }
}
