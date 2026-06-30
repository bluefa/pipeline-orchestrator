package com.bff.pipeline.service.task.type;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.utils.TaskSettings;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * 외부 조건이 충족될 때까지 폴링하는 {@link TaskType} 구현체이다.
 * 디스패치할 대상이 없고 — 작업 자체가 폴링이므로 — {@code execute}는 no-op이다.
 * {@code check}는 조건을 탐색하며, task별 TTL(Time-to-Live)의 제약을 받는다.
 * TTL이 만료되면 재시도하지 <em>않는다</em>(ADR-016 §6): 대기 창(wait window)이 소진된 것이지
 * 호출이 실패한 것이 아니기 때문이다. 타입 이름 {@link #NAME}은 모든 condition task 행에
 * 저장되며, recipe에서 참조되고 registry에 의해 해석된다.
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
    public void execute(String target, Task task) {
    }

    @Override
    public TaskProgress check(String target, Task task) {
        if (infraManager.checkCondition(target, task.getOperation())) {
            return TaskProgress.SUCCEEDED;
        }
        if (TaskSettings.isPastDeadline(task, TaskSettings.resolveTimeToLive(task, settings), clock)) {
            return TaskProgress.failedTerminal(ErrorCode.TIME_TO_LIVE_EXPIRED);
        }
        return TaskProgress.pending(CheckSignal.NOT_MET);
    }
}
