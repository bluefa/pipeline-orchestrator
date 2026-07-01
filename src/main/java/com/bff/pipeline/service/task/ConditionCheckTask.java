package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import org.springframework.stereotype.Component;

/**
 * 외부 조건이 충족될 때까지 폴링하는 {@link TaskType} 구현체다. 작업 자체가 폴링이라 디스패치할 대상이 없으므로
 * {@code execute}는 no-op이다. {@code check}는 조건을 한 번 살핀다. 조건이 아직 안 맞으면 재시도 가능한
 * {@code CONDITION_NOT_MET} 실패를 돌려주어, 엔진의 {@code failCount}/{@code maxFailCount} 예산이 대기 횟수를 제한하게 한다.
 * 별도의 wall-clock 대기 창(TTL)은 두지 않는다 — maxFailCount번 재확인해도 조건이 안 맞으면 태스크가 FAILED로 종료된다.
 * 타입 이름 {@link #NAME}은 모든 condition task 행에 저장되며, recipe가 이를 참조하고 registry가 해석한다.
 */
@Component
public class ConditionCheckTask implements TaskType {

    public static final String NAME = TaskOperation.Mechanism.CONDITION_CHECK;

    private final InfraManagerClient infraManagerClient;

    public ConditionCheckTask(InfraManagerClient infraManagerClient) {
        this.infraManagerClient = infraManagerClient;
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
        return TaskProgress.failedRetryable(ErrorCode.CONDITION_NOT_MET);
    }
}
