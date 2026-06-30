package com.bff.pipeline.service;

import com.bff.pipeline.ExecutionSettings;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * ADR-021 클레임-풀 사이클: claim(tx1) → 외부 호출(트랜잭션 밖) → report(tx2).
 * 클레임한 파이프라인 id를 반환하며, 클레임할 파이프라인이 없으면 empty를 반환한다.
 * 예외는 전파 — ADR-021 스케줄러(S5)가 파이프라인별로 격리한다.
 *
 * <p>{@code cancelRequested}는 두 안전 지점에서 확인된다: 클레임 직후({@code loadStepContext})와
 * tx2({@code report} 내부). isTerraformDispatch 슬롯 게이트: 소프트 slotCap 초과 시
 * {@code reschedule}하고 클레임을 해제한다(ADR Decision 7).
 *
 * <p>현재 태스크가 없으면(all-terminal: 클레임된 RUNNING 파이프라인에서는 드문 경우)
 * {@code report(outcome=null)}을 호출하여 converge + release만 수행한다.
 */
@Component
public class PipelineWorker {

    private record StepContext(
            String target,
            Task currentTask,
            boolean cancelRequested,
            boolean isTerraformDispatch) {}

    private final PipelineClaimer claimer;
    private final StepReporter reporter;
    private final StepRunner stepRunner;
    private final TaskRepository tasks;
    private final PipelineRepository pipelines;
    private final ExecutionSettings settings;
    private final Clock clock;

    public PipelineWorker(PipelineClaimer claimer, StepReporter reporter, StepRunner stepRunner,
            TaskRepository tasks, PipelineRepository pipelines, ExecutionSettings settings, Clock clock) {
        this.claimer = claimer;
        this.reporter = reporter;
        this.stepRunner = stepRunner;
        this.tasks = tasks;
        this.pipelines = pipelines;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 단일 claim→process 사이클 진입점(테스트 및 편의용). 프로덕션 스윕({@link PipelineScheduler#drain})은
     * {@code claimer.claimOneDue()} + {@code process(...)}를 직접 호출하여 클레임 단계 실패와
     * 처리 단계 실패를 분리한다.
     */
    public Optional<Long> pollOnce() {
        Optional<PipelineClaimer.Claim> claimed = claimer.claimOneDue();
        if (claimed.isEmpty()) return Optional.empty();
        PipelineClaimer.Claim claim = claimed.get();
        process(claim);
        return Optional.of(claim.pipelineId());
    }

    public void process(PipelineClaimer.Claim claim) {
        StepContext context = loadStepContext(claim.pipelineId());
        if (context == null) {
            reporter.report(claim.pipelineId(), claim.token(), null);
            return;
        }
        if (context.cancelRequested()) {
            reporter.reportCancel(claim.pipelineId(), claim.token());
            return;
        }
        if (context.currentTask() == null) {
            reporter.report(claim.pipelineId(), claim.token(), null);
            return;
        }
        if (context.isTerraformDispatch() && !slotAvailable()) {
            reporter.reschedule(claim.pipelineId(), claim.token(), settings.slotRetry());
            return;
        }
        StepOutcome outcome = stepRunner.runStep(context.target(), context.currentTask());
        reporter.report(claim.pipelineId(), claim.token(), outcome);
    }

    private StepContext loadStepContext(long pipelineId) {
        Pipeline pipeline = pipelines.findById(pipelineId).orElse(null);
        if (pipeline == null) return null;
        List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipelineId);
        Task current = chain.stream().filter(t -> !t.getStatus().isTerminal()).findFirst().orElse(null);
        boolean isTerraformDispatch = current != null
                && current.getStatus() == TaskStatus.READY
                && TerraformTask.NAME.equals(current.getTaskName());
        return new StepContext(pipeline.getTarget(), current, pipeline.isCancelRequested(), isTerraformDispatch);
    }

    private boolean slotAvailable() {
        return tasks.countByTaskNameAndStatus(TerraformTask.NAME, TaskStatus.IN_PROGRESS) < settings.slotCap();
    }
}
