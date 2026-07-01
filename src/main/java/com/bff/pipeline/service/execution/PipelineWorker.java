package com.bff.pipeline.service.execution;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.dto.Claim;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.task.ObservationRecorder;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import org.springframework.stereotype.Component;

/**
 * 한 실행 사이클을 조립한다: claim(claim 트랜잭션, {@link PipelineClaimer}) → 외부 호출(run 단계, {@link StepRunner})
 * → write-back(write-back 트랜잭션, {@link StepReporter})를 잇는다. <b>자신은 트랜잭션을 열지 않는다</b> — 외부 호출이 어떤
 * 행 락도 쥐고 있지 않게 하기 위해서다(Decision 3). 트랜잭션은 pipelineClaimer와 reporter에만 있다.
 *
 * <p>cancel은 두 안전지점에서 관찰한다. 하나는 claim 직후로, {@link #loadStepContext}에서 {@code cancel_requested}이거나
 * 현재 task가 없으면 외부 호출을 건너뛰고 write-back으로 넘어가 수렴·해제한다. 다른 하나는 write-back 트랜잭션 안이다({@link StepReporter#writeBack}).
 * terraform slot을 소비하는 dispatch 단계인데 빈 slot이 없으면 외부 호출 대신 reschedule로 claim을 놓는다(Decision 7).
 */
@Component
public class PipelineWorker {

    @Builder
    private record StepContext(String target, Task currentTask, TaskAttempt attempt,
            boolean cancelRequested, boolean terraformSlotDispatch) { }

    private final PipelineClaimer pipelineClaimer;
    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final ObservationRecorder observationRecorder;
    private final StepRunner stepRunner;
    private final StepReporter stepReporter;
    private final ExecutionSettings executionSettings;

    public PipelineWorker(PipelineClaimer pipelineClaimer, PipelineRepository pipelineRepository, TaskRepository taskRepository,
            ObservationRecorder observationRecorder, StepRunner stepRunner,
            StepReporter stepReporter, ExecutionSettings executionSettings) {
        this.pipelineClaimer = pipelineClaimer;
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.observationRecorder = observationRecorder;
        this.stepRunner = stepRunner;
        this.stepReporter = stepReporter;
        this.executionSettings = executionSettings;
    }

    /**
     * 테스트 seam: 한 건을 claim해 처리하고 그 pipeline id를 반환한다(잡을 게 없으면 empty). 프로덕션 경로는
     * {@code PipelineScheduler.drain()}이 {@link #process}를 부르는 것이고, 이 메서드는 스케줄러 없이 한 사이클을
     * 결정적으로 돌려 검증하려는 테스트만 쓴다({@code claimOneDue} + {@code process} 합성).
     */
    public Optional<Long> pollOnce() {
        return pipelineClaimer.claimOneDue().map(claim -> {
            process(claim);
            return claim.pipelineId();
        });
    }

    public void process(Claim claim) {
        loadStepContext(claim.pipelineId()).ifPresent(context -> processStep(claim, context));
    }

    private void processStep(Claim claim, StepContext context) {
        if (nothingToDispatch(context)) {
            stepReporter.writeBack(claim.pipelineId(), claim.token(), null);
            return;
        }
        if (mustWaitForTerraformSlot(context)) {
            stepReporter.reschedule(claim.pipelineId(), claim.token(), executionSettings.terraformSlotRetry());
            return;
        }
        StepOutcome outcome = stepRunner.runStep(context.target(), context.currentTask(), context.attempt());
        stepReporter.writeBack(claim.pipelineId(), claim.token(), outcome);
    }

    /**
     * 던질 외부 작업이 없는 경우다 — 취소가 요청됐거나(그러면 write-back 트랜잭션이 취소를 적용) 남은 비종료 task가 없다(그러면 write-back 트랜잭션이
     * 종료 상태로 수렴). 어느 쪽이든 외부 호출을 건너뛰고 {@code outcome=null}로 write-back해 수렴·claim 해제만 맡긴다.
     */
    private boolean nothingToDispatch(StepContext context) {
        return context.cancelRequested() || context.currentTask() == null;
    }

    /** 이번 스텝이 terraform 슬롯을 소비하는 dispatch인데 빈 슬롯이 없는 경우다 — 외부 호출 대신 잠시 뒤로 미룬다(reschedule). */
    private boolean mustWaitForTerraformSlot(StepContext context) {
        return context.terraformSlotDispatch() && !terraformSlotAvailable();
    }

    private Optional<StepContext> loadStepContext(long pipelineId) {
        return pipelineRepository.findById(pipelineId).map(pipeline -> {
            List<Task> chain = taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId);
            Task current = currentTask(chain).orElse(null);
            TaskAttempt attempt = current == null ? null : observationRecorder.currentAttempt(current).orElse(null);
            boolean terraformSlotDispatch = current != null
                    && current.getStatus() == TaskStatus.READY
                    && Boolean.TRUE.equals(current.getConsumesTerraformSlot());
            return StepContext.builder()
                    .target(pipeline.getTarget())
                    .currentTask(current)
                    .attempt(attempt)
                    .cancelRequested(pipeline.isCancelRequested())
                    .terraformSlotDispatch(terraformSlotDispatch)
                    .build();
        });
    }

    private Optional<Task> currentTask(List<Task> chain) {
        return chain.stream().filter(task -> !task.getStatus().isTerminal()).findFirst();
    }

    private boolean terraformSlotAvailable() {
        return taskRepository.countByConsumesTerraformSlotIsTrueAndStatus(TaskStatus.IN_PROGRESS)
                < executionSettings.terraformSlotCap();
    }
}
