package com.bff.pipeline.service;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.utils.TaskKnobs;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * A task that polls an external condition until it holds. There is nothing to dispatch — the work is
 * the polling — so {@code attempt} is a no-op; {@code check} probes the condition, bounded by the
 * per-task TTL. An expired TTL does <em>not</em> retry (ADR-016 §6): the wait window is gone, not the
 * call. The type name {@link #NAME} is stored on every condition task row (referenced by recipes,
 * resolved by the registry).
 */
@Component
public class ConditionCheckTask implements TaskType {

    public static final String NAME = "CONDITION_CHECK";

    private final InfraManagerClient infraManager;
    private final PipelineSettings settings;
    private final Clock clock;

    public ConditionCheckTask(InfraManagerClient infraManager, PipelineSettings settings, Clock clock) {
        this.infraManager = infraManager;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public void attempt(String target, Task task) {
    }

    @Override
    public TaskProgress check(String target, Task task) {
        if (infraManager.checkCondition(target, task.getOperation())) {
            return TaskProgress.SUCCEEDED;
        }
        if (TaskKnobs.pastDeadline(task, TaskKnobs.timeToLive(task, settings), clock)) {
            return TaskProgress.failed(ErrorCode.TIME_TO_LIVE_EXPIRED, false);
        }
        return TaskProgress.pending(CheckSignal.NOT_MET);
    }
}
