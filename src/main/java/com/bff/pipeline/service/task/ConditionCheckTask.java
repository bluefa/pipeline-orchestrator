package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import org.springframework.stereotype.Component;

/**
 * 외부 조건이 충족될 때까지 폴링하는 {@link TaskType} 구현체다. 작업 자체가 폴링이라 디스패치할 대상이 없으므로
 * {@code execute}는 no-op이다. {@code check}는 조건을 한 번 살핀다 — 충족되면 성공, 아니면 not-met이다.
 * not-met 폴은 실패한 폴이라 failCount를 올리고 polling_interval 뒤 다시 확인하며(ADR-016 §6), maxFailCount에
 * 닿으면 CONDITION_NOT_MET으로 실패한다. 시간 경계(TTL)가 아니라 재시도 횟수 경계다. 매 폴은 원시 check payload를
 * 그 폴의 {@code task_attempt.response}에 남긴다. 타입 이름 {@link #NAME}은 모든 condition task 행에 저장되며,
 * recipe가 이를 참조하고 registry가 해석한다.
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
        return pollCondition(target, task);
    }

    /** 조건 폴은 attempt의 response를 읽지 않으므로, 관찰 유실과 무관하게 같은 폴을 수행한다. */
    @Override
    public TaskProgress checkWithoutAttempt(String target, Task task) {
        return pollCondition(target, task);
    }

    private TaskProgress pollCondition(String target, Task task) {
        ConditionPoll poll = infraManagerClient.checkCondition(target, task.getOperation());
        if (poll == null) {   // 경계 방어: 쓸 수 없는 외부 응답은 raw NPE가 아니라 닫힌 어휘로(TerraformTask와 동일)
            throw new CallFailedException("InfraManager returned no condition result for " + task.getOperation());
        }
        return poll.met()
                ? TaskProgress.met(poll.response())
                : TaskProgress.notMet(poll.response());
    }
}
