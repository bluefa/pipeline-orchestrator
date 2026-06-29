package com.bff.pipeline.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves a task row's {@code taskName} to its {@link TaskType}. Built from every {@code TaskType}
 * bean, so adding a new type registers it automatically — no central list to edit. A name with no
 * matching type means the task is no longer a defined task, and the engine fails it
 * ({@code ErrorCode.UNKNOWN_TASK}) rather than guessing.
 *
 * <p>The map is validated at construction: a {@code TaskType} with a null/blank name, or two types
 * claiming the same name, fails application start with a message naming the offending type(s) — a
 * misconfiguration surfaces at boot, not as a corrupted row silently resolving to the wrong type.
 * Lookup is null-safe, returning the type for a name or empty if none is registered under it.
 */
@Component
public class TaskTypeRegistry {

    private final Map<String, TaskType> byName;

    public TaskTypeRegistry(List<TaskType> taskTypes) {
        Map<String, TaskType> map = new HashMap<>();
        for (TaskType type : taskTypes) {
            String name = type.taskName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "TaskType " + type.getClass().getName() + " has a null/blank taskName()");
            }
            TaskType clash = map.putIfAbsent(name, type);
            if (clash != null) {
                throw new IllegalStateException("Two TaskTypes claim taskName '" + name + "': "
                        + clash.getClass().getName() + " and " + type.getClass().getName());
            }
        }
        this.byName = Map.copyOf(map);
    }

    public Optional<TaskType> find(String taskName) {
        return Optional.ofNullable(byName.get(taskName));
    }
}
