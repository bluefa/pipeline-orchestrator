package com.bff.pipeline.service.task;

import com.bff.pipeline.service.task.type.TaskType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * task 행의 {@code taskName}을 해당 {@link TaskType}으로 해석(resolve)하는 레지스트리이다.
 * 모든 {@code TaskType} 빈(bean)으로부터 구성되므로, 새로운 타입을 추가하면 자동으로 등록된다 —
 * 중앙 목록을 편집할 필요가 없다. 이름에 매칭되는 타입이 없으면 해당 task는 더 이상 정의된 타입이
 * 아님을 의미하며, 엔진은 추측하지 않고 {@code ErrorCode.UNKNOWN_TASK}로 실패 처리한다.
 *
 * <p>맵은 생성 시점에 유효성 검사를 거친다: null 또는 빈 이름을 가진 {@code TaskType},
 * 또는 같은 이름을 주장하는 두 타입이 있으면 위반 타입 이름을 명시한 메시지와 함께 애플리케이션
 * 시작에 실패한다 — 잘못된 설정은 부트 시점에 표면화되며, 손상된 행이 조용히 잘못된 타입으로
 * 해석되는 일은 없다. 조회는 null-safe하며, 등록된 이름에 해당하는 타입 또는 미등록 이름의 경우
 * empty를 반환한다.
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
        if (taskName == null) { return Optional.empty(); }
        return Optional.ofNullable(byName.get(taskName));
    }
}
