package com.bff.pipeline.service;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADR-021 phase-A: 외부 호출 경계이다. tx1(claim)과 tx2(report) <b>사이</b>에서, 어떤 트랜잭션에도
 * 속하지 않은 채 실행된다(Decision 3 — 외부호출 동안 행 락을 보유하지 않기 위함). 이 모듈에서
 * InfraManager 외부 호출({@link TaskType#execute}/{@link TaskType#check})이 실제로 일어나는 유일한 곳이다.
 *
 * <p>닫힌 어휘({@link InfraManagerClient.CallTimeoutException}/{@link InfraManagerClient.CallFailedException})만
 * {@link StepOutcome}으로 번역한다. {@link InfraManagerClient.CallInterruptedException}과 그 외 순수
 * {@code RuntimeException}(진짜 버그)은 캐치하지 않고 전파한다(fail-fast; 스케줄러가 pipeline 단위로 격리).
 * 비즈니스 실패는 예외가 아니라 {@code TaskProgress}/{@code StepOutcome} 값이다({@code docs/exception-strategy.md}).
 *
 * <p>결과 {@link StepOutcome}은 phase-B({@link StepReporter}→{@link TaskStateMachine})가 tx2 안에서
 * 태스크 전환으로 적용한다. {@code @Transactional}이 없다 — 트랜잭션 밖 실행이 이 클래스의 핵심 계약이다.
 */
@Component
public class StepRunner {

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    private final TaskTypeRegistry taskTypes;

    public StepRunner(TaskTypeRegistry taskTypes) {
        this.taskTypes = taskTypes;
    }

    public StepOutcome runStep(String target, Task task, TaskAttempt attempt) {
        TaskType type = taskTypes.find(task.getTaskName()).orElse(null);
        if (type == null) {
            return StepOutcome.unknownTask();
        }
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
        } catch (InfraManagerClient.CallTimeoutException exception) {
            log.warn("InfraManager call timed out for task {} ({})", task.getId(), task.getTaskName());
            return StepOutcome.callTimeout(dispatch);
        } catch (InfraManagerClient.CallFailedException exception) {
            log.warn("InfraManager call failed for task {} ({}): {}", task.getId(), task.getTaskName(),
                    exception.getMessage());
            return StepOutcome.callFailed(dispatch);
        }
    }
}
