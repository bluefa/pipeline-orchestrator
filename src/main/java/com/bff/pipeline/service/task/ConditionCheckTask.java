package com.bff.pipeline.service.task;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.utils.TaskSettingsResolver;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * 외부 조건이 충족될 때까지 폴링하는 {@link TaskType} 구현체다. 작업 자체가 폴링이라 디스패치할 대상이 없으므로
 * {@code execute}는 no-op이다. {@code check}는 조건을 살피며 task별 TTL(Time-to-Live)의 제약을 받는다. TTL이 만료되면
 * 재시도하지 <em>않는다</em>(ADR-016 §6). 호출이 실패한 게 아니라 대기 창(wait window)이 다 소진된 것이기 때문이다.
 * 타입 이름 {@link #NAME}은 모든 condition task 행에 저장되며, recipe가 이를 참조하고 registry가 해석한다.
 */
@Component
public class ConditionCheckTask implements TaskType {

    public static final String NAME = TaskOperation.Mechanism.CONDITION_CHECK;

    private final InfraManagerClient infraManagerClient;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public ConditionCheckTask(InfraManagerClient infraManagerClient, PipelineSettings pipelineSettings, Clock clock) {
        this.infraManagerClient = infraManagerClient;
        this.pipelineSettings = pipelineSettings;
        this.clock = clock;
    }

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public DispatchResult execute(String target, Task task) {
        return DispatchResult.NONE;
    }

    @Override
    public TaskProgress check(String target, Task task, TaskAttempt attempt) {
        if (infraManagerClient.checkCondition(target, task.getOperation())) {
            return TaskProgress.SUCCEEDED;
        }
        if (TaskSettingsResolver.isPastDeadline(task, TaskSettingsResolver.resolveTimeToLive(task, pipelineSettings), clock)) {
            return TaskProgress.failedTerminal(ErrorCode.TIME_TO_LIVE_EXPIRED);
        }
        return TaskProgress.pending(CheckSignal.NOT_MET);
    }
}
