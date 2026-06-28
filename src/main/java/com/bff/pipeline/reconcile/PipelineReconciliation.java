package com.bff.pipeline.reconcile;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.Task;
import com.bff.pipeline.domain.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles ONE pipeline in its own committed transaction (ADR-016 §1–§2). Split from
 * {@link Reconciler} so the {@code @Transactional} boundary is a real proxied call — each pipeline
 * advances and commits independently.
 *
 * <p>The transaction re-reads the pipeline first and skips a non-RUNNING one, so a cancel that
 * committed between the scan and now is honored. The cancel-during-call race is then closed by the
 * task's {@code @Version} optimistic lock (see {@code docs/exception-strategy.md}).
 */
@Component
public class PipelineReconciliation {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskMachine machine;
    private final Clock clock;

    public PipelineReconciliation(PipelineRepository pipelines, TaskRepository tasks, TaskMachine machine, Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.machine = machine;
        this.clock = clock;
    }

    @Transactional
    public void reconcile(Long pipelineId) {
        Pipeline pipeline = pipelines.findById(pipelineId).orElse(null);
        if (pipeline == null || pipeline.getStatus() != PipelineStatus.RUNNING) {
            return; // cancelled or already converged between the scan and now
        }
        List<Task> chain = tasks.findByPipelineIdOrderBySeqAsc(pipelineId);
        Task current = currentTask(chain);
        if (current != null && isDue(current)) {
            machine.advance(pipeline.getTarget(), current);
        }
        converge(pipelineId);
    }

    /** The current task is the lowest-seq non-terminal one; higher-seq tasks stay BLOCKED behind it. */
    private Task currentTask(List<Task> chain) {
        return chain.stream()
                .filter(task -> !task.getStatus().isTerminal())
                .findFirst()
                .orElse(null);
    }

    private boolean isDue(Task task) {
        return task.getNextCheckAt() == null || !task.getNextCheckAt().isAfter(clock.instant());
    }

    /**
     * Any task FAILED → pipeline FAILED (and its remaining tasks CANCELLED, ADR-016 §7); all DONE →
     * pipeline DONE. Both terminalize via the RUNNING-guarded finish() CAS, so a converge can never
     * overwrite a pipeline a concurrent cancel already moved to CANCELLED.
     */
    private void converge(Long pipelineId) {
        List<Task> chain = tasks.findByPipelineIdOrderBySeqAsc(pipelineId);
        if (chain.stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
            cancelRemaining(chain);
            pipelines.finish(pipelineId, PipelineStatus.FAILED, clock.instant());
        } else if (chain.stream().allMatch(task -> task.getStatus() == TaskStatus.DONE)) {
            pipelines.finish(pipelineId, PipelineStatus.DONE, clock.instant());
        }
    }

    private void cancelRemaining(List<Task> chain) {
        Instant now = clock.instant();
        for (Task task : chain) {
            if (!task.getStatus().isTerminal()) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setFinishedAt(now);
                tasks.save(task);
            }
        }
    }
}
