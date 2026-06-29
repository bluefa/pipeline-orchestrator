package com.bff.pipeline.service;

import com.bff.pipeline.PipelineSettings;
import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.utils.TaskSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 태스크 하나를 한 단계 전진시킨다 (ADR-016 §2, §6). 현재 태스크는 {@link PipelineEngine}이 전달하며,
 * 이 클래스는 태스크별 전환을 소유하되 타입별 세부 작업은 해당 태스크의 {@link TaskType}
 * ({@link TaskTypeRegistry}를 통해 이름으로 조회)에 위임한다. 따라서 태스크 종류에 대한
 * {@code switch} 분기가 없다.
 *
 * <pre>
 *   BLOCKED      → unblock → READY                         (선행 태스크가 DONE 도달)
 *   READY        → type.execute (멱등 디스패치)             → IN_PROGRESS
 *   IN_PROGRESS  → type.check → Succeeded → type.postCheck → DONE (실패 시 재예약/재시도)
 *                              Pending   → reschedule
 *                              Failed    → retry-or-fail (or fail outright if not retryable)
 * </pre>
 *
 * <p>저장된 태스크 이름에 등록된 타입이 없으면 태스크는 {@code UNKNOWN_TASK}로 즉시 실패 처리된다
 * (ADR-016 §2). 이는 비즈니스 값이며, 다른 종료 태스크와 동일하게 파이프라인을 실패시킨다.
 * 언블로킹은 선행 태스크가 DONE에 도달하면 BLOCKED 태스크를 READY로 전환하여, 다음 advance에서
 * 디스패치 가능한 상태로 만든다.
 *
 * <p><b>불변식.</b> 디스패치는 멱등적이다 (ADR-016 §5): IN_PROGRESS 저장 이전에 장애가 발생해도
 * 다음 advance에서 재디스패치로 복구된다. 재시도 시 job id는 초기화되고 {@code nextCheckAt}은
 * null로 설정되어 새 디스패치가 즉시 실행 가능한 상태가 된다. 시도 관찰 행은 태스크 종료와 동일
 * 트랜잭션에서 종료 처리되므로, 커밋 후 종료된 태스크 뒤에 IN_PROGRESS 상태의 관찰이 남지 않는다.
 * 또한 {@code failCount}가 증가하기 전에 종료 처리되므로, 정확한 {@code attempt_number}에 기록된다.
 * 재시도 불가 실패(예: TTL이 만료되어 대기 구간이 소멸된 경우)는 추가 시도 없이 즉시 태스크를
 * 실패 처리한다.
 *
 * <p><b>예외 전략:</b> {@link TaskType}의 {@code execute}, {@code check}, {@code postCheck}가 수행하는
 * 모든 외부 호출은 단일 {@code runExternalCall} 경계를 통해 변환된다
 * ({@link InfraManagerClient.CallTimeoutException} → CALL_TIMEOUT,
 * {@link InfraManagerClient.CallFailedException} → CHECK_ERROR). 변환 전에 반드시
 * 로그를 기록한다. {@link InfraManagerClient.CallInterruptedException}과 순수 {@code RuntimeException}
 * (진짜 버그)은 캐치하지 않고 그대로 전파된다(fail-fast). 비즈니스 결과는 절대 예외가 아니며 행에 기록되는
 * {@code ErrorCode} 값이다. {@code docs/exception-strategy.md}를 참조한다.
 */
@Component
public class TaskMachine {

    private static final Logger log = LoggerFactory.getLogger(TaskMachine.class);

    private final TaskTypeRegistry taskTypes;
    private final TaskRepository tasks;
    private final Observations observations;
    private final PipelineSettings settings;
    private final Clock clock;

    public TaskMachine(TaskTypeRegistry taskTypes, TaskRepository tasks, Observations observations,
            PipelineSettings settings, Clock clock) {
        this.taskTypes = taskTypes;
        this.tasks = tasks;
        this.observations = observations;
        this.settings = settings;
        this.clock = clock;
    }

    void advance(String target, Task task) {
        switch (task.getStatus()) {
            case BLOCKED -> unblock(task);
            case READY -> dispatch(target, task);
            case IN_PROGRESS -> poll(target, task);
            case DONE, FAILED, CANCELLED -> { }
        }
    }

    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        tasks.save(task);
    }

    private void dispatch(String target, Task task) {
        TaskType type = typeOrFail(task);
        if (type == null) return;
        observations.beginAttempt(task);
        runExternalCall(task, () -> { type.execute(target, task); return TaskProgress.SUCCEEDED; }, false)
                .ifPresent(ignored -> markInProgress(task));
    }

    private void poll(String target, Task task) {
        TaskType type = typeOrFail(task);
        if (type == null) return;
        runExternalCall(task, () -> type.check(target, task), true)
                .ifPresent(progress -> applyCheck(type, target, task, progress));
    }

    private void applyCheck(TaskType type, String target, Task task, TaskProgress progress) {
        switch (progress) {
            case TaskProgress.Succeeded ignored -> afterCheckSucceeded(type, target, task);
            case TaskProgress.Pending pending -> recordPendingAndReschedule(task, pending);
            case TaskProgress.Failed failed -> applyFailure(task, failed);
        }
    }

    private void afterCheckSucceeded(TaskType type, String target, Task task) {
        runExternalCall(task, () -> type.postCheck(target, task), true)
                .ifPresent(progress -> applyPostCheck(task, progress));
    }

    private void applyPostCheck(Task task, TaskProgress progress) {
        switch (progress) {
            case TaskProgress.Succeeded ignored -> complete(task);
            case TaskProgress.Pending pending -> recordPendingAndReschedule(task, pending);
            case TaskProgress.Failed failed -> applyFailure(task, failed);
        }
    }

    private void recordPendingAndReschedule(Task task, TaskProgress.Pending pending) {
        observations.recordCheck(task, pending.observed());
        reschedule(task, TaskSettings.resolvePollingInterval(task, settings));
    }

    private void applyFailure(Task task, TaskProgress.Failed failed) {
        if (failed.retryable()) retryOrFail(task, failed.reason());
        else failOutright(task, failed.reason());
    }

    private void failUnknownTask(Task task) {
        failOutright(task, ErrorCode.UNKNOWN_TASK);
    }

    private TaskType typeOrFail(Task task) {
        TaskType type = taskTypes.find(task.getTaskName()).orElse(null);
        if (type == null) failUnknownTask(task);
        return type;
    }

    private Optional<TaskProgress> runExternalCall(Task task, Supplier<TaskProgress> call, boolean recordObservation) {
        try {
            return Optional.of(call.get());
        } catch (InfraManagerClient.CallTimeoutException exception) {
            log.warn("InfraManager call timed out for task {} ({})", task.getId(), task.getTaskName());
            if (recordObservation) observations.recordCheck(task, CheckSignal.CALL_TIMEOUT);
            retryOrFail(task, ErrorCode.CALL_TIMEOUT);
            return Optional.empty();
        } catch (InfraManagerClient.CallFailedException exception) {
            log.warn("InfraManager call failed for task {} ({}): {}", task.getId(), task.getTaskName(), exception.getMessage());
            if (recordObservation) observations.recordCheck(task, CheckSignal.API_ERROR);
            retryOrFail(task, ErrorCode.CHECK_ERROR);
            return Optional.empty();
        }
    }

    private void failOutright(Task task, ErrorCode reason) {
        observations.endAttempt(task, TaskStatus.FAILED, reason);
        fail(task, reason);
    }

    private void markInProgress(Task task) {
        if (task.getJobId() != null) {
            observations.recordJobId(task, task.getJobId());
        }
        task.setStatus(TaskStatus.IN_PROGRESS);
        Instant now = clock.instant();
        task.setStartedAt(now);
        task.setNextCheckAt(now);
        tasks.save(task);
    }

    private void retryOrFail(Task task, ErrorCode reason) {
        observations.endAttempt(task, TaskStatus.FAILED, reason);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskSettings.resolveMaxFailCount(task, settings)) {
            fail(task, reason);
            return;
        }
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        task.setJobId(null);
        task.setNextCheckAt(null);
        tasks.save(task);
    }

    private void complete(Task task) {
        task.setStatus(TaskStatus.DONE);
        task.setFinishedAt(clock.instant());
        tasks.save(task);
        observations.endAttempt(task, TaskStatus.DONE, null);
    }

    private void fail(Task task, ErrorCode reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setErrorCode(reason);
        task.setFinishedAt(clock.instant());
        tasks.save(task);
    }

    private void reschedule(Task task, Duration after) {
        task.setNextCheckAt(clock.instant().plus(after));
        tasks.save(task);
    }
}
