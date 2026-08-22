package com.bff.pipeline.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * StepRunner가 외부 호출 전에 행을 진실원(task_definition)으로 검증하는 부분을 다룬다. 승인 대기 분기도
 * 여기서 함께 고정한다 — 그 분기의 요점이 "task type을 아예 부르지 않는다"이기 때문이다.
 */
class StepRunnerTest {

    private final StepRunner stepRunner = new StepRunner(new TaskTypeRegistry(List.of(
            fake(TaskOperation.Mechanism.TERRAFORM_JOB),
            fake(TaskOperation.Mechanism.CONDITION_CHECK),
            fake(TaskOperation.Mechanism.APPROVAL))));

    @Test
    void dispatchesWhenTheRowAgreesWithItsDefinition() {
        StepOutcome outcome = stepRunner.runStep("t", readyTaskOf(TaskDefinition.AWS_SERVICE_APPLY_V1), null);

        assertThat(outcome).isInstanceOf(StepOutcome.Dispatched.class);
    }

    @Test
    void failsAsUnknownTaskWhenTheDefinitionIsMissing() {
        Task task = readyTaskOf(TaskDefinition.AWS_SERVICE_APPLY_V1);
        task.setTaskDefinition(null);

        assertThat(stepRunner.runStep("t", task, null)).isInstanceOf(StepOutcome.UnknownTask.class);
    }

    @Test
    void failsAsUnknownTaskWhenTheDefinitionNameDoesNotResolve() {
        Task task = readyTaskOf(TaskDefinition.AWS_SERVICE_APPLY_V1);
        task.setTaskDefinition("AWS_SERVICE_APPLY_V999");

        assertThat(stepRunner.runStep("t", task, null)).isInstanceOf(StepOutcome.UnknownTask.class);
    }

    @Test
    void failsAsUnknownTaskWhenTheCachedOperationDisagreesWithTheDefinition() {
        Task task = readyTaskOf(TaskDefinition.AWS_SERVICE_APPLY_V1);
        task.setOperation(TaskOperation.AWS_SERVICE_TF_DESTROY);   // 캐시 컬럼이 정의와 어긋남 (손상)

        assertThat(stepRunner.runStep("t", task, null)).isInstanceOf(StepOutcome.UnknownTask.class);
    }

    @Test
    void failsAsUnknownTaskWhenTheCachedSlotFlagDisagreesWithTheDefinition() {
        Task task = readyTaskOf(TaskDefinition.AWS_SERVICE_APPLY_V1);   // 정의는 slot 소비 true
        task.setConsumesTerraformSlot(false);                       // 캐시가 어긋남 → slot 게이트 우회 위험

        assertThat(stepRunner.runStep("t", task, null)).isInstanceOf(StepOutcome.UnknownTask.class);
    }

    /**
     * 승인 대기 중인 게이트는 폴링하지 않는다. 무엇으로 전이할지는 승인 행을 잠근 마무리 트랜잭션이 정해야
     * 하므로, run 단계는 판정을 미루는 결과만 돌려주고 task type은 건드리지 않는다 — 여기 있는 가짜
     * task type은 check가 불리면 성공을 돌려주므로, 결과가 ApprovalPoll이라는 것이 곧 안 불렸다는 증거다.
     */
    @Test
    void anAwaitingGateDefersTheDecisionInsteadOfPolling() {
        Task gate = readyTaskOf(TaskDefinition.AWS_SERVICE_APPLY_APPROVAL_V1);
        gate.setStatus(TaskStatus.AWAIT_APPROVAL);

        assertThat(stepRunner.runStep("t", gate, null)).isInstanceOf(StepOutcome.ApprovalPoll.class);
    }

    private static Task readyTaskOf(TaskDefinition definition) {
        return Task.builder()
                .id(1L)
                .taskName(definition.mechanism())
                .operation(definition.operation())
                .taskDefinition(definition.name())
                .consumesTerraformSlot(definition.consumesTerraformSlot())
                .status(TaskStatus.READY)
                .build();
    }

    private static TaskType fake(String name) {
        return new TaskType() {
            @Override public String taskName() { return name; }
            @Override public DispatchResult execute(String target, Task task) { return DispatchResult.withResponse("[\"job-1\"]"); }
            @Override public TaskProgress check(String target, Task task, TaskAttempt attempt) { return TaskProgress.SUCCEEDED; }
            @Override public TaskProgress checkWithoutAttempt(String target, Task task) { return TaskProgress.SUCCEEDED; }
        };
    }
}
