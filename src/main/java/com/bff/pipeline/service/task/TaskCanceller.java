package com.bff.pipeline.service.task;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.execution.StepReporter;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 체인의 비종료 태스크를 취소 처리하면서, 열려 있던 시도 관찰(attempt observation)도 각각 CANCELLED로 닫아 이력이
 * 거짓말을 하지 않도록 한다. 관리자 취소({@link PipelineControl})와 수렴 시의 실패 연쇄({@link StepReporter})가 이 컴포넌트를
 * 함께 쓴다. 취소 로직을 한곳에 모아 두면 두 호출 지점이 DRY를 지키고, 태스크가 종료되기 전에 관찰이 반드시 먼저 닫힘을 보장한다.
 */
@Component
@RequiredArgsConstructor
public class TaskCanceller {

    private final TaskRepository taskRepository;
    private final ObservationRecorder observationRecorder;
    private final Clock clock;

    public void cancelNonTerminal(List<Task> chain) {
        Instant now = clock.instant();
        chain.stream().filter(task -> !task.getStatus().isTerminal()).forEach(task -> {
            observationRecorder.endAttempt(task, TaskStatus.CANCELLED, null, null);
            task.setStatus(TaskStatus.CANCELLED);
            task.setFinishedAt(now);
            taskRepository.save(task);
        });
    }
}
