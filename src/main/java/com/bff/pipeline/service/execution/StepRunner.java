package com.bff.pipeline.service.execution;
import com.bff.pipeline.service.task.TaskTypeRegistry;
import com.bff.pipeline.service.task.TaskStateMachine;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.exception.CallInterruptedException;
import com.bff.pipeline.exception.CallFailedException;

/**
 * run 단계 — 외부 호출 경계다. claim 트랜잭션과 write-back 트랜잭션 <b>사이</b>에서 어떤 트랜잭션에도 속하지 않은 채
 * 실행된다(Decision 3 — 외부 호출 동안에는 행 락을 쥐지 않는다). InfraManager 외부 호출
 * ({@link TaskType#execute}/{@link TaskType#check})이 실제로 일어나는 곳은 이 모듈뿐이다.
 *
 * <p>{@link StepOutcome}으로 번역하는 것은 닫힌 어휘
 * ({@link CallTimeoutException}/{@link CallFailedException})뿐이다.
 * {@link CallInterruptedException}과 그 밖의 순수 {@code RuntimeException}(진짜 버그)은
 * 잡지 않고 그대로 전파한다(fail-fast — 스케줄러가 pipeline 단위로 격리한다). 비즈니스 실패는 예외가 아니라
 * {@code TaskProgress}/{@code StepOutcome} 값으로 표현한다({@code docs/exception-strategy.md}).
 *
 * <p>결과 {@link StepOutcome}은 write-back 단계({@link StepReporter}→{@link TaskStateMachine})가 write-back 트랜잭션 안에서 태스크
 * 전환으로 적용한다. {@code @Transactional}이 없다 — 트랜잭션 밖에서 도는 것이 이 클래스의 핵심 계약이다.
 */
@Slf4j
@Component
public class StepRunner {

    private final TaskTypeRegistry taskTypeRegistry;

    public StepRunner(TaskTypeRegistry taskTypeRegistry) {
        this.taskTypeRegistry = taskTypeRegistry;
    }

    public StepOutcome runStep(String target, Task task, TaskAttempt attempt) {
        return resolveConsistentType(task)
                .map(type -> run(target, task, attempt, type))
                .orElseGet(StepOutcome::unknownTask);
    }

    /**
     * 외부 호출 전에 행을 진실원(task_definition)과 대조해 검증한다(설계 §4). 정의가 미해석이거나(삭제/rename),
     * mechanism이 registry에 없거나, 정의가 정한 mechanism/operation/slot-flag가 행의 캐시 컬럼과 어긋나면
     * (=DB 손상/버그) 어떤 TaskType도 부르지 않고 empty를 돌려 UNKNOWN_TASK로 끊는다.
     */
    private Optional<TaskType> resolveConsistentType(Task task) {
        Optional<TaskDefinition> definition = TaskDefinition.find(task.getTaskDefinition());
        if (definition.isEmpty()) {
            log.warn("task {} has an unresolved definition '{}' — failing as UNKNOWN_TASK",
                    task.getId(), task.getTaskDefinition());
            return Optional.empty();
        }
        TaskDefinition resolved = definition.get();
        if (rowCacheDisagreesWith(resolved, task)) {
            log.warn("task {} definition {} disagrees with row cache (mechanism '{}' op {} slot {}) — failing as UNKNOWN_TASK",
                    task.getId(), resolved.name(), task.getTaskName(), task.getOperation(), task.getConsumesTerraformSlot());
            return Optional.empty();
        }
        return taskTypeRegistry.find(task.getTaskName());
    }

    /** 행의 write-once 캐시(mechanism/operation/slot-flag)가 진실원인 정의와 어긋나는지 — 어긋나면 DB 손상/버그다. */
    private static boolean rowCacheDisagreesWith(TaskDefinition definition, Task task) {
        return !definition.mechanism().equals(task.getTaskName())
                || definition.operation() != task.getOperation()
                || definition.consumesTerraformSlot() != Boolean.TRUE.equals(task.getConsumesTerraformSlot());
    }

    private StepOutcome run(String target, Task task, TaskAttempt attempt, TaskType type) {
        return switch (task.getStatus()) {
            case BLOCKED -> StepOutcome.unblock();
            case READY -> runExternalCall(task, true, () -> StepOutcome.dispatched(type.execute(target, task)));
            case IN_PROGRESS -> runExternalCall(task, false, () -> mapProgress(type.check(target, task, attempt)));
            case DONE, FAILED, CANCELLED ->
                    throw new IllegalStateException("runStep on a terminal task " + task.getId());
        };
    }

    private StepOutcome mapProgress(TaskProgress progress) {
        return switch (progress) {
            case TaskProgress.Succeeded ignored -> StepOutcome.succeeded();
            case TaskProgress.Pending pending -> StepOutcome.pending(pending.observed());
            case TaskProgress.Failed failed -> StepOutcome.failed(failed.reason(), failed.retryable());
        };
    }

    private StepOutcome runExternalCall(Task task, boolean dispatch, Supplier<StepOutcome> call) {
        try {
            return call.get();
        } catch (CallTimeoutException exception) {
            log.warn("InfraManager call timed out for task {} ({})", task.getId(), task.getTaskName());
            return StepOutcome.callTimeout(dispatch);
        } catch (CallFailedException exception) {
            log.warn("InfraManager call failed for task {} ({}): {}", task.getId(), task.getTaskName(),
                    exception.getMessage());
            return StepOutcome.callFailed(dispatch);
        }
    }
}
