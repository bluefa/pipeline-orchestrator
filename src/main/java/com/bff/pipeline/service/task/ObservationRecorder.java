package com.bff.pipeline.service.task;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskCheck;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.model.HttpExchange;
import com.bff.pipeline.repository.TaskConfirmationInputRepository;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Task별 attempt와 폴링 요약을 상태 기록 트랜잭션에 함께 저장한다. 현재 attempt 번호는 failCount + 1이며
 * 실패 횟수를 올리기 전에 해당 attempt를 종결한다. 기존 응답 계약은 유지하고 HTTP 본문·metadata는 별도 컬럼에
 * 기록한다. HTTP 입력/실행이 정상인데 관찰 행만 유실된 경우 현재 attempt를 복구해 실제 결과를 보존한다.
 *
 * 이 클래스는 자체 독립 트랜잭션을 열지 않는다. 점유 소유권과 취소를 확인한 호출자의 트랜잭션에 참여하므로
 * 응답 저장 실패는 Task 상태 변경까지 롤백한다. 입력 참조는 등록에 사용한 Task별 스냅샷을 추적한다.
 * 호출 없는 종결의 빈 exchange는 기존 HTTP 증적을 유지한다. 실제 호출의 무응답은 operation이 있는 exchange로
 * 구분하며, 이때는 이전 응답 대신 해당 호출의 빈 본문과 상태를 기록한다.
 */
@Component
@RequiredArgsConstructor
public class ObservationRecorder {

    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskCheckRepository taskCheckRepository;
    private final Clock clock;
    private final TaskConfirmationInputRepository confirmationInputs;

    public void beginAttempt(Task task) {
        taskAttemptRepository.save(TaskAttempt.builder()
                .taskId(task.getId())
                .attemptNumber(attemptNumber(task))
                .status(TaskStatus.IN_PROGRESS)
                .startedAt(clock.instant())
                .build());
    }

    public void ensureAttempt(Task task) {
        if (currentAttempt(task).isEmpty()) beginAttempt(task);
    }

    public void recordHttpResponse(Task task, HttpExchange exchange, Long inputId) {
        ensureAttempt(task);
        currentAttempt(task).ifPresent(attempt -> {
            if (exchange != null) {
                attempt.setHttpResponse(exchange.body());
                attempt.setHttpOperation(exchange.operation());
                attempt.setHttpStatusCode(exchange.statusCode());
                attempt.setResponseContentType(exchange.contentType());
                attempt.setResponseReceivedAt(exchange.receivedAt());
                attempt.setResponseTruncated(exchange.truncated());
            }
            if (inputId != null) attempt.setConfirmationInputId(inputId);
            else confirmationInputs.findByTaskId(task.getId()).ifPresent(input -> attempt.setConfirmationInputId(input.getId()));
            taskAttemptRepository.save(attempt);
        });
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
