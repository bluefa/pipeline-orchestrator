package com.bff.pipeline.service;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskCheck;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 관찰 테이블({@code task_attempt}, {@code task_check}; ADR-016 §3)의 단일 기록자(writer)이다. 엔진은 완료 판정을
 * 위해 {@link #currentAttempt} 최신 행만 읽는다(§3 invariant 1); 그 외 claim/스케줄링/전이는 관찰을 읽지 않는다. 일반적인 경우
 * (attempt 행이 없는 경우)에는 no-op으로 처리되어 복원력을 갖는다. 엔진의 {@code advance}
 * 트랜잭션을 함께 사용한다 — 별도의 {@code REQUIRES_NEW} 트랜잭션이 아니다(비동기 관찰 분리
 * 방안은 기각됨). 쓰기 실패 시 전체 advance가 롤백되고 재시도되며, 상태가 손상되는 경우는 없다.
 * {@code docs/exception-strategy.md} 참조.
 *
 * <p>현재 시도(attempt)는 {@code (task.id, attemptNumber = task.failCount + 1)}으로 식별된다 —
 * {@code failCount}는 시도가 종료될 때만 변경되므로 시도 전체에 걸쳐 안정적이다.
 *
 * <p>{@code beginAttempt}는 task가 디스패치 단계에 진입할 때 새 시도를 개시한다.
 * {@code recordResponse}는 디스패치가 반환한 원시 {@code response}(형식 불문, 해석은 task type 소관)를 최신 시도에 저장한다.
 * {@code recordCheck}는 폴 한 번의 결과를 시도의 단일 check 행에 요약한다 — 최초 사용 시
 * 생성되고 이후에는 제자리 갱신된다(RUNNING 신호는 호출 횟수만 증가시키고, 나머지 신호는
 * 각자의 서브 카운터를 증가시킨다). {@code endAttempt}는 시도의 최종 결과를 기록한다.
 */
@Component
public class ObservationRecorder {

    private final TaskAttemptRepository attempts;
    private final TaskCheckRepository checks;
    private final Clock clock;

    public ObservationRecorder(TaskAttemptRepository attempts, TaskCheckRepository checks, Clock clock) {
        this.attempts = attempts;
        this.checks = checks;
        this.clock = clock;
    }

    public void beginAttempt(Task task) {
        attempts.save(TaskAttempt.builder()
                .taskId(task.getId())
                .attemptNumber(attemptNumber(task))
                .status(TaskStatus.IN_PROGRESS)
                .startedAt(clock.instant())
                .build());
    }

    public void recordResponse(Task task, String response) {
        currentAttempt(task).ifPresent(attempt -> {
            attempt.setResponse(response);
            attempts.save(attempt);
        });
    }

    public void recordCheck(Task task, CheckSignal signal) {
        Optional<TaskAttempt> attempt = currentAttempt(task);
        if (attempt.isEmpty()) {
            return;
        }
        TaskAttempt current = attempt.get();
        TaskCheck check = currentCheck(current);
        check.setCallCount(check.getCallCount() + 1);
        switch (signal) {
            case NOT_MET -> check.setNotMetCount(check.getNotMetCount() + 1);
            case API_ERROR -> check.setApiErrorCount(check.getApiErrorCount() + 1);
            case CALL_TIMEOUT -> check.setCallTimeoutCount(check.getCallTimeoutCount() + 1);
            case RUNNING -> { }
        }
        check.setLastExternalStatus(signal.name());
        check.setLastCheckedAt(clock.instant());
        checks.save(check);
    }

    public void endAttempt(Task task, TaskStatus outcome, ErrorCode errorCode) {
        currentAttempt(task).ifPresent(attempt -> {
            attempt.setStatus(outcome);
            attempt.setErrorCode(errorCode);
            attempt.setFinishedAt(clock.instant());
            attempts.save(attempt);
        });
    }

    /**
     * 완료 판정의 입력이 되는 <b>최신(=현재) attempt</b> 행을 반환한다(ADR-016 §3 invariant 1: 엔진은 관찰 테이블을
     * 오직 완료 목적으로, 최신 행만 읽는다). 키는 {@code (task.id, failCount+1)}이며 {@code failCount}는 시도가 종료될
     * 때만 변하므로 시도 전체에 걸쳐 안정적이다. 비어 있으면(유실) 호출자는 executionTimeout fallthrough로 처리한다.
     */
    public Optional<TaskAttempt> currentAttempt(Task task) {
        return attempts.findByTaskIdAndAttemptNumber(task.getId(), attemptNumber(task));
    }

    private TaskCheck currentCheck(TaskAttempt attempt) {
        return checks.findByTaskAttemptId(attempt.getId())
                .orElseGet(() -> TaskCheck.builder().taskAttemptId(attempt.getId()).build());
    }

    private static int attemptNumber(Task task) {
        return task.getFailCount() + 1;
    }
}
