package com.bff.pipeline.service.task;
import com.bff.pipeline.service.execution.StepReporter;
import com.bff.pipeline.service.execution.StepRunner;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CheckSignal;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.HttpTaskResult;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.utils.TaskSettingsResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 외부 호출이 끝난 뒤 결과를 Task 상태와 attempt 기록에 적용한다. 호출자는 Pipeline 행을 잠그고 점유 토큰을
 * 확인한 뒤 취소가 우선인지 검사한다. 이 클래스는 그 트랜잭션에 참여하며 외부 API를 호출하지 않는다.
 *
 * 기존 Terraform/Condition은 응답 기록과 폴 결과에 따라 진행한다. HTTP 추천 GET은 입력을 고정하고 같은
 * Task를 IN_PROGRESS로 유지한다. 삭제 또는 저장된 입력의 POST는 HTTP 완료 값으로 종결한다. 연결 테스트의
 * 접수는 실행 version을 고정하고 관찰을 예약한다. 이후 정상/오류 관찰은 전용 기록자가 변환한 결과를 같은
 * attempt에 적용한다. 응답과 입력 저장이 실패하면 모든 상태 변경이 함께 롤백된다.
 *
 * 재시도는 현재 attempt를 종료한 뒤 failCount를 증가시키고 정해진 간격 뒤 READY로 전환한다. 종결 성공과
 * 실패는 호출자의 수렴 단계가 후속 승격 또는 Pipeline 종료로 반영한다. 새로운 결과는 exhaustive switch로
 * 빠짐없이 처리하며 mechanism 이름 문자열로 실행 종류를 추측하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TaskStateMachine {

    private final TaskRepository taskRepository;
    private final ObservationRecorder observationRecorder;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;
    private final TaskConfirmationInputs confirmationInputs;
    private final TestConnectionExecutionService testConnections;
    private final TestConnectionResultRecorder testConnectionResults;

    public void applyOutcome(Task task, StepOutcome outcome) {
        if (outcome.dispatchPhase()) observationRecorder.beginAttempt(task);
        switch (outcome) {
            case StepOutcome.Unblock ignored -> unblock(task);
            case StepOutcome.Dispatched dispatched -> markInProgress(task, dispatched.dispatchResult());
            case StepOutcome.TestConnectionPolled polled -> {
                observationRecorder.ensureAttempt(task);
                applyOutcome(task, testConnectionResults.observe(task, polled.observation()));
            }
            case StepOutcome.HttpCompleted completed -> completeHttp(task, completed.result());
            case StepOutcome.Pending pending -> recordPendingAndReschedule(task, pending.observed());
            case StepOutcome.Succeeded ignored -> complete(task);
            case StepOutcome.Failed failed -> applyFailure(task, failed.reason(), failed.retryable(), failed.detail());
            case StepOutcome.CallFailure callFailure -> {
                if (callFailure.exchange() != null) observationRecorder.recordHttpResponse(task, callFailure.exchange(), null);
                if (!callFailure.dispatch()) observationRecorder.recordCheck(task, callFailure.signal());
                applyFailure(task, callFailure.reason(), callFailure.retryable(), callFailure.detail());
            }
            case StepOutcome.ConditionMet met -> completeCondition(task, met.response());
            case StepOutcome.ConditionNotMet notMet -> retryCondition(task, notMet.response());
            case StepOutcome.UnknownTask ignored -> failOutright(task, ErrorCode.UNKNOWN_TASK);
        }
    }

    /** CONDITION_CHECK 충족 폴: 그 폴의 payload와 MET 관찰을 남기고 task를 완료한다(ADR-016 §6). */
    private void completeCondition(Task task, String response) {
        observationRecorder.recordResponse(task, response);
        observationRecorder.recordCheck(task, CheckSignal.MET);
        complete(task);
    }

    /** CONDITION_CHECK 미충족 폴 = 실패한 폴: payload와 NOT_MET 관찰을 남기고 failCount 예산으로 재시도/실패시킨다. */
    private void retryCondition(Task task, String response) {
        observationRecorder.recordResponse(task, response);
        observationRecorder.recordCheck(task, CheckSignal.NOT_MET);
        retryOrFail(task, ErrorCode.CONDITION_NOT_MET, null);
    }

    private void applyFailure(Task task, ErrorCode reason, boolean retryable, String failureDetail) {
        if (retryable) retryOrFail(task, reason, failureDetail);
        else failOutright(task, reason, failureDetail);
    }

    private void unblock(Task task) {
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(clock.instant());
        testConnections.markReady(task, task.getReadyAt());
        taskRepository.save(task);
    }

    private void markInProgress(Task task, DispatchResult result) {
        switch (result) {
            case DispatchResult.WithResponse response -> {
                observationRecorder.recordResponse(task, response.response());
                start(task);
            }
            case DispatchResult.None ignored -> start(task);
            case DispatchResult.TestConnectionStarted started -> startTestConnection(task, started);
            case DispatchResult.HttpPrepared prepared -> {
                Long inputId = confirmationInputs.capture(task, prepared.recommendation());
                observationRecorder.recordHttpResponse(task, prepared.recommendation().exchange(), inputId);
                start(task);
            }
            case DispatchResult.HttpCompleted completed -> {
                start(task);
                completeHttp(task, completed.result());
            }
        }
    }

    private void startTestConnection(Task task, DispatchResult.TestConnectionStarted started) {
        HttpTaskResult binding = testConnections.started(task, started.response());
        observationRecorder.recordHttpResponse(task, started.response().exchange(), null);
        start(task);
        if (binding.success()) reschedule(task, TaskSettingsResolver.resolvePollingInterval(task, pipelineSettings));
        else completeHttp(task, binding);
    }

    private void start(Task task) {
        Instant now = clock.instant();
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setStartedAt(now);
        task.setNextCheckAt(now);
        taskRepository.save(task);
    }

    private void completeHttp(Task task, HttpTaskResult result) {
        observationRecorder.recordHttpResponse(task, result.exchange(), result.confirmationInputId());
        if (result.success()) complete(task);
        else applyFailure(task, result.errorCode(), result.retryable(), result.detail());
    }

    private void recordPendingAndReschedule(Task task, CheckSignal observed) {
        observationRecorder.recordCheck(task, observed);
        reschedule(task, TaskSettingsResolver.resolvePollingInterval(task, pipelineSettings));
    }

    private void failOutright(Task task, ErrorCode reason) {
        failOutright(task, reason, null);
    }

    private void failOutright(Task task, ErrorCode reason, String failureDetail) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason, failureDetail);
        fail(task, reason);
    }

    private void retryOrFail(Task task, ErrorCode reason, String failureDetail) {
        observationRecorder.endAttempt(task, TaskStatus.FAILED, reason, failureDetail);
        task.setFailCount(task.getFailCount() + 1);
        if (task.getFailCount() >= TaskSettingsResolver.resolveMaxFailCount(task, pipelineSettings)) {
            fail(task, reason);
            return;
        }
        Instant now = clock.instant();
        task.setStatus(TaskStatus.READY);
        task.setReadyAt(now);
        task.setNextCheckAt(now.plus(TaskSettingsResolver.resolvePollingInterval(task, pipelineSettings)));
        taskRepository.save(task);
    }

    private void complete(Task task) {
        task.setStatus(TaskStatus.DONE);
        task.setFinishedAt(clock.instant());
        taskRepository.save(task);
        observationRecorder.endAttempt(task, TaskStatus.DONE, null, null);
    }

    private void fail(Task task, ErrorCode reason) {
        task.setStatus(TaskStatus.FAILED);
        task.setErrorCode(reason);
        task.setFinishedAt(clock.instant());
        taskRepository.save(task);
    }

    private void reschedule(Task task, Duration after) {
        task.setNextCheckAt(clock.instant().plus(after));
        taskRepository.save(task);
    }
}
