package com.bff.pipeline.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** registry의 이름 해석과, 카탈로그가 광고하는 모든 TaskDefinition mechanism이 등록됐는지에 대한 부팅 검증을 다룬다. */
class TaskTypeRegistryTest {

    /** 카탈로그의 모든 mechanism에 대응하는 TaskType이 있으면 부팅되고, 이름을 그 타입으로 해석한다. */
    @Test
    void resolvesRegisteredNamesWhenEveryDefinitionMechanismIsPresent() {
        TaskTypeRegistry registry = new TaskTypeRegistry(everyMechanism());

        for (String mechanism : mechanismsInCatalog()) {
            assertThat(registry.find(mechanism)).isPresent();
        }
        assertThat(registry.find("NOT_A_TYPE")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    /** 카탈로그가 광고하는 mechanism에 대응 TaskType이 없으면 부팅이 실패한다 — 런타임 확정 실패로 부팅되지 않게. */
    @Test
    void failsBootWhenACatalogMechanismHasNoRegisteredType() {
        List<TaskType> missingOne = everyMechanism().stream()
                .filter(type -> !type.taskName().equals(TaskDefinition.APPLY_NETWORK_V1.mechanism()))
                .collect(Collectors.toList());

        assertThatThrownBy(() -> new TaskTypeRegistry(missingOne))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TaskDefinition.APPLY_NETWORK_V1.mechanism());
    }

    @Test
    void rejectsTwoTypesClaimingTheSameName() {
        List<TaskType> clashing = new ArrayList<>(everyMechanism());
        clashing.add(fake(TaskDefinition.APPLY_NETWORK_V1.mechanism()));

        assertThatThrownBy(() -> new TaskTypeRegistry(clashing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim taskName");
    }

    private static Set<String> mechanismsInCatalog() {
        return java.util.Arrays.stream(TaskDefinition.values())
                .map(TaskDefinition::mechanism)
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<TaskType> everyMechanism() {
        return mechanismsInCatalog().stream()
                .map(this::fake)
                .collect(Collectors.toList());
    }

    private TaskType fake(String name) {
        return new TaskType() {
            @Override public String taskName() { return name; }
            @Override public DispatchResult execute(String target, Task task) { return DispatchResult.NONE; }
            @Override public TaskProgress check(String target, Task task, TaskAttempt attempt) { return TaskProgress.SUCCEEDED; }
        };
    }
}
