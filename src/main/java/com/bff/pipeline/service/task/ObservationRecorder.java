package com.bff.pipeline.service.task;

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
 * 관찰 테이블({@code task_attempt}, {@code task_check}; ADR-016 §3)의 유일한 기록자(writer)다. 엔진은 완료 판정을 위해
 * {@link #currentAttempt}의 최신 행만 읽고(§3 invariant 1), claim/스케줄링/전이는 관찰을 아예 읽지 않는다. attempt 행이 없는
 * 흔한 경우에는 no-op으로 넘어가 복원력을 유지한다. 엔진의 {@code advance} 트랜잭션에 얹혀 동작하며, 별도의
 * {@code REQUIRES_NEW} 트랜잭션을 쓰지 않는다(비동기로 관찰을 떼어내는 방안은 기각됐다). 쓰기가 실패하면 advance 전체가
 * 롤백된 뒤 재시도되므로 상태가 깨질 여지가 없다. {@code docs/exception-strategy.md} 참조.
 *
 * <p>현재 시도는 {@code (task.id, attemptNumber = task.failCount + 1)}로 식별한다. {@code failCount}는 시도가 끝날 때만
 * 바뀌므로 한 시도 내내 값이 안정적이다.
 *
 * <p>{@code beginAttempt}는 task가 디스패치 단계에 들어설 때 새 시도를 연다. {@code recordResponse}는 디스패치가 돌려준
 * 원시 {@code response}(형식은 불문, 해석은 task type의 몫)를 최신 시도에 저장한다. {@code recordCheck}는 폴 한 번의 결과를
 * 시도의 단일 check 행에 요약한다 — 처음 호출 때 만들고 이후에는 제자리에서 갱신한다(RUNNING 신호는 호출 횟수만 올리고,
 * 나머지 신호는 각자의 서브 카운터를 올린다). {@code endAttempt}는 시도의 최종 결과를 기록한다 — 실패 종결에는
 * 원인 텍스트({@code failureDetail})를 함께 남기되, 외부 유래 텍스트이므로 컬럼 길이로 잘라 저장 실패를 막는다.
 */
@Component
public class ObservationRecorder {

    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskCheckRepository taskCheckRepository;
    private final Clock clock;

    public ObservationRecorder(TaskAttemptRepository taskAttemptRepository, TaskCheckRepository taskCheckRepository, Clock clock) {
        this.taskAttemptRepository = taskAttemptRepository;
        this.taskCheckRepository = taskCheckRepository;
        this.clock = clock;
    }

    public void beginAttempt(Task task) {
        taskAttemptRepository.save(TaskAttempt.builder()
                .taskId(task.getId())
                .attemptNumber(attemptNumber(task))
                .status(TaskStatus.IN_PROGRESS)
                .startedAt(clock.instant())
                .build());
    }

    public void recordResponse(Task task, String response) {
        currentAttempt(task).ifPresent(attempt -> {
            attempt.setResponse(response);
            taskAttemptRepository.save(attempt);
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
            case RUNNING, MET -> { }
        }
        check.setLastExternalStatus(signal.name());
        check.setLastCheckedAt(clock.instant());
        taskCheckRepository.save(check);
    }

    public void endAttempt(Task task, TaskStatus outcome, ErrorCode errorCode, String failureDetail) {
        currentAttempt(task).ifPresent(attempt -> {
            attempt.setStatus(outcome);
            attempt.setErrorCode(errorCode);
            attempt.setFailureDetail(clampFailureDetail(failureDetail));
            attempt.setFinishedAt(clock.instant());
            taskAttemptRepository.save(attempt);
        });
    }

    /** failureDetail은 외부 유래 텍스트다 — 컬럼 길이를 넘으면 잘라 저장이 무결성 위반으로 깨지지 않게 방어한다. */
    private static String clampFailureDetail(String failureDetail) {
        if (failureDetail == null || failureDetail.length() <= TaskAttempt.FAILURE_DETAIL_LENGTH) {
            return failureDetail;
        }
        return failureDetail.substring(0, TaskAttempt.FAILURE_DETAIL_LENGTH);
    }

    /**
     * 완료 판정의 입력이 되는 <b>최신(=현재) attempt</b> 행을 반환한다(ADR-016 §3 invariant 1: 엔진은 관찰 테이블을 오직
     * 완료 목적으로 최신 행만 읽는다). 키는 {@code (task.id, failCount+1)}이고, {@code failCount}는 시도가 끝날 때만 바뀌므로
     * 한 시도 내내 안정적이다. 값이 비어 있으면(유실) 호출자는 executionTimeout fallthrough로 처리한다.
     */
    public Optional<TaskAttempt> currentAttempt(Task task) {
        return taskAttemptRepository.findByTaskIdAndAttemptNumber(task.getId(), attemptNumber(task));
    }

    private TaskCheck currentCheck(TaskAttempt attempt) {
        return taskCheckRepository.findByTaskAttemptId(attempt.getId())
                .orElseGet(() -> TaskCheck.builder().taskAttemptId(attempt.getId()).build());
    }

    private static int attemptNumber(Task task) {
        return task.getFailCount() + 1;
    }
}
