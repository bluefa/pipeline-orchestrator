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
import com.bff.pipeline.service.task.terraform.TerraformTask;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 한 실행 사이클을 조립한다: claim(tx1, {@link PipelineClaimer}) → 외부 호출(phase-A, {@link StepRunner})
 * → report(tx2, {@link StepReporter})를 잇는다. <b>자신은 트랜잭션을 열지 않는다</b> — 외부 호출이 어떤
 * 행 락도 쥐고 있지 않게 하기 위해서다(Decision 3). 트랜잭션은 pipelineClaimer와 reporter에만 있다.
 *
 * <p>cancel은 두 안전지점에서 관찰한다. 하나는 claim 직후로, {@link #loadStepContext}에서 {@code cancel_requested}이거나
 * 현재 task가 없으면 외부 호출을 건너뛰고 report로 넘어가 수렴·해제한다. 다른 하나는 tx2 안이다({@link StepReporter#report}).
 * TF dispatch 단계인데 slot이 비어 있지 않으면 외부 호출 대신 reschedule로 claim을 놓는다(Decision 7).
 */
@Component
public class PipelineWorker {

    private record StepContext(String target, Task currentTask, TaskAttempt attempt,
            boolean cancelRequested, boolean terraformDispatch) { }

    private final PipelineClaimer pipelineClaimer;
    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final ObservationRecorder observationRecorder;
    private final StepRunner stepRunner;
    private final StepReporter stepReporter;
    private final ExecutionSettings executionSettings;

    public PipelineWorker(PipelineClaimer pipelineClaimer, PipelineRepository pipelineRepository, TaskRepository taskRepository,
            ObservationRecorder observationRecorder, StepRunner stepRunner, StepReporter stepReporter,
            ExecutionSettings executionSettings) {
        this.pipelineClaimer = pipelineClaimer;
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.observationRecorder = observationRecorder;
        this.stepRunner = stepRunner;
        this.stepReporter = stepReporter;
        this.executionSettings = executionSettings;
    }

    /** 편의 메서드: 한 건을 claim해 처리하고 그 pipeline id를 반환한다. 잡을 게 없으면 empty. */
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
        if (context.cancelRequested() || context.currentTask() == null) {
            stepReporter.report(claim.pipelineId(), claim.token(), null);
            return;
        }
        if (context.terraformDispatch() && !slotAvailable()) {
            stepReporter.reschedule(claim.pipelineId(), claim.token(), executionSettings.slotRetry());
            return;
        }
        StepOutcome outcome = stepRunner.runStep(context.target(), context.currentTask(), context.attempt());
        stepReporter.report(claim.pipelineId(), claim.token(), outcome);
    }

    private Optional<StepContext> loadStepContext(long pipelineId) {
        return pipelineRepository.findById(pipelineId).map(pipeline -> {
            List<Task> chain = taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId);
            Task current = currentTask(chain).orElse(null);
            TaskAttempt attempt = current == null ? null : observationRecorder.currentAttempt(current).orElse(null);
            boolean terraformDispatch = current != null
                    && current.getStatus() == TaskStatus.READY
                    && TerraformTask.NAME.equals(current.getTaskName());
            return new StepContext(pipeline.getTarget(), current, attempt, pipeline.isCancelRequested(), terraformDispatch);
        });
    }

    private Optional<Task> currentTask(List<Task> chain) {
        return chain.stream().filter(task -> !task.getStatus().isTerminal()).findFirst();
    }

    private boolean slotAvailable() {
        return taskRepository.countByTaskNameAndStatus(TerraformTask.NAME, TaskStatus.IN_PROGRESS) < executionSettings.slotCap();
    }
}
