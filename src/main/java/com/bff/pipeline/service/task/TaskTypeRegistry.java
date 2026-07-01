package com.bff.pipeline.service.task;

import com.bff.pipeline.model.TaskType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * task 행의 {@code taskName}을 해당 {@link TaskType}으로 풀어 주는 레지스트리다. 모든 {@code TaskType} 빈(bean)으로
 * 구성되므로 새 타입을 추가하면 자동으로 등록되며, 중앙 목록을 손댈 필요가 없다. 이름에 맞는 타입이 없다는 것은 그 task가
 * 더 이상 정의된 타입이 아니라는 뜻이고, 이때 엔진은 넘겨짚지 않고 {@code ErrorCode.UNKNOWN_TASK}로 실패 처리한다.
 *
 * <p>맵은 생성 시점에 검증한다. 이름이 null이거나 빈 {@code TaskType}이 있거나 같은 이름을 주장하는 두 타입이 있으면,
 * 위반한 타입 이름을 담은 메시지와 함께 애플리케이션 기동이 실패한다 — 잘못된 설정은 부트 시점에 드러나고, 손상된 행이
 * 조용히 엉뚱한 타입으로 해석되는 일은 없다. 조회는 null-safe하며, 등록된 이름이면 그 타입을, 미등록 이름이면 empty를 돌려준다.
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
