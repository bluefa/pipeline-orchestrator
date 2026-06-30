package com.bff.pipeline.service.task;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.execution.StepReporter;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 체인의 비종료 태스크들을 취소 처리하고, 열려 있는 시도 관찰(attempt observation)을 각각
 * CANCELLED로 종료하여 이력이 거짓말을 하지 않도록 한다. 관리자 취소({@link PipelineControl})와
 * 수렴 시 실패 연쇄({@link StepReporter})가 이 컴포넌트를 공유한다. 취소 로직을 여기에 집중하면
 * 두 호출 지점이 DRY를 유지하며, 태스크가 종료되기 전에 항상 관찰이 종료 처리됨을 보장한다.
 */
@Component
public class TaskCanceller {

    private final TaskRepository tasks;
    private final ObservationRecorder observationRecorder;
    private final Clock clock;

    public TaskCanceller(TaskRepository tasks, ObservationRecorder observationRecorder, Clock clock) {
        this.tasks = tasks;
        this.observationRecorder = observationRecorder;
        this.clock = clock;
    }

    public void cancelNonTerminal(List<Task> chain) {
        Instant now = clock.instant();
        chain.stream().filter(task -> !task.getStatus().isTerminal()).forEach(task -> {
            observationRecorder.endAttempt(task, TaskStatus.CANCELLED, null);
            task.setStatus(TaskStatus.CANCELLED);
            task.setFinishedAt(now);
            tasks.save(task);
        });
    }
}
