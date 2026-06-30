package com.bff.pipeline.service;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-021 phase-A: 단일 외부 호출 경계(외부 호출, 트랜잭션 밖). {@code TaskType.execute/check/postCheck}를
 * 어떤 트랜잭션도 열지 않은 상태에서 실행하고, 닫힌 어휘(closed vocabulary)인
 * {@link InfraManagerClient.CallTimeoutException} / {@link InfraManagerClient.CallFailedException}만을
 * {@link StepOutcome}으로 변환한다. {@link InfraManagerClient.CallInterruptedException}과 그 외
 * {@code RuntimeException}(진짜 버그)은 캐치하지 않고 전파(fail-fast). 비즈니스 실패는 예외가 아닌
 * {@code StepOutcome} 변형(variant)으로 반환된다.
 *
 * <p>이 클래스는 시스템에서 외부 InfraManager 호출이 발생하는 유일한 지점이다. phase-B인
 * {@link StepReporter}가 이 결과를 tx2에서 {@link TaskMachine}에 전달하여 적용한다.
 *
 * <p><b>BLOCKED 안전망:</b> {@code case BLOCKED → unblock()} 분기는 불변식 복구용 안전망이다.
 * 정상 흐름에서는 tx2({@link StepReporter})가 BLOCKED 태스크를 READY로 승격하므로 phase-A가
 * BLOCKED 태스크를 보는 경우는 드물다.
 */
@Component
public class StepRunner {

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    private final TaskTypeRegistry taskTypes;

    public StepRunner(TaskTypeRegistry taskTypes) {
        this.taskTypes = taskTypes;
    }

    public StepOutcome runStep(String target, Task task) {
        TaskType type = taskTypes.find(task.getTaskName()).orElse(null);
        if (type == null) return StepOutcome.unknownTask();
        return switch (task.getStatus()) {
            case BLOCKED -> StepOutcome.unblock();
            case READY -> dispatch(type, target, task);
            case IN_PROGRESS -> poll(type, target, task);
            case DONE, FAILED, CANCELLED -> throw new IllegalStateException("runStep on a terminal task " + task.getId());
        };
    }

    private StepOutcome dispatch(TaskType type, String target, Task task) {
        return runExternalCall(task, true, () -> {
            type.execute(target, task);
            return StepOutcome.dispatched(task.getJobId());
        });
    }

    private StepOutcome poll(TaskType type, String target, Task task) {
        return runExternalCall(task, false, () -> {
            TaskProgress progress = type.check(target, task);
            TaskProgress resolved = (progress instanceof TaskProgress.Succeeded)
                    ? type.postCheck(target, task)
                    : progress;
            return switch (resolved) {
                case TaskProgress.Succeeded ignored -> StepOutcome.succeeded();
                case TaskProgress.Pending p -> StepOutcome.pending(p.observed());
                case TaskProgress.Failed f -> StepOutcome.failed(f.reason(), f.retryable());
            };
        });
    }

    private StepOutcome runExternalCall(Task task, boolean dispatch, Supplier<StepOutcome> body) {
        try {
            return body.get();
        } catch (InfraManagerClient.CallTimeoutException e) {
            log.warn("InfraManager call timed out for task {} ({})", task.getId(), task.getTaskName());
            return StepOutcome.callTimeout(dispatch);
        } catch (InfraManagerClient.CallFailedException e) {
            log.warn("InfraManager call failed for task {} ({}): {}", task.getId(), task.getTaskName(), e.getMessage());
            return StepOutcome.callFailed(dispatch);
        }
    }
}
