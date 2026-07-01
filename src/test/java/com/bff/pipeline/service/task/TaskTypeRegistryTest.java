package com.bff.pipeline.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-021 terraform slot 게이트의 집계 기반: registry가 슬롯-소비 타입 이름을 어떻게 모으는지 검증한다. */
class TaskTypeRegistryTest {

    /**
     * terraformSlotTaskNames는 {@code consumesTerraformSlot()==true}인 <b>모든</b> 타입 이름을 모으고 나머지는 뺀다.
     * 슬롯을 공유하는 terraform 계열 타입이 여럿이어도 하나도 놓치지 않아야 cap 카운트가 헐거워지지 않는다.
     */
    @Test
    void collectsEveryTerraformSlotConsumingTypeName() {
        TaskTypeRegistry registry = new TaskTypeRegistry(List.of(
                fake("TERRAFORM_JOB", true),
                fake("AWS_APPLY", true),
                fake("CONDITION_CHECK", false)));

        assertThat(registry.terraformSlotTaskNames())
                .containsExactlyInAnyOrder("TERRAFORM_JOB", "AWS_APPLY");
    }

    @Test
    void isEmptyWhenNoTypeConsumesTheSlot() {
        TaskTypeRegistry registry = new TaskTypeRegistry(List.of(fake("CONDITION_CHECK", false)));

        assertThat(registry.terraformSlotTaskNames()).isEmpty();
    }

    private TaskType fake(String name, boolean consumesSlot) {
        return new TaskType() {
            @Override public String taskName() { return name; }
            @Override public DispatchResult execute(String target, Task task) { return DispatchResult.NONE; }
            @Override public TaskProgress check(String target, Task task, TaskAttempt attempt) { return TaskProgress.SUCCEEDED; }
            @Override public boolean consumesTerraformSlot() { return consumesSlot; }
        };
    }
}
