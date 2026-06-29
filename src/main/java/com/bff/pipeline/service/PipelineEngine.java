package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The domain operation that advances ONE pipeline one step, in its own committed transaction
 * (ADR-016 §1–§2): pick the current task (lowest-sequence non-terminal), advance it, then converge the
 * pipeline. This is the only state-mutating entry point for forward progress.
 *
 * <p>The current task is the lowest-sequence non-terminal one; higher-sequence tasks stay BLOCKED
 * behind it. Convergence then settles the run: any task FAILED moves the pipeline to FAILED and
 * CANCELs its remaining non-terminal tasks (ADR-016 §7); all tasks DONE moves it to DONE. Both
 * terminalize through the RUNNING-guarded {@code finish()} CAS, so a converge can never overwrite a
 * pipeline a concurrent cancel already moved to CANCELLED. {@code machine.advance} mutates the current
 * managed task in the in-memory chain, so converge reads the new state without re-reading;
 * {@code finish()}'s flush makes it durable.
 *
 * <p><b>Execution is out of scope.</b> <em>When</em>, <em>how often</em>, and <em>with what
 * concurrency</em> {@code advance} is called — the runner, scheduling, worker pool, crash recovery —
 * is the separate, independently-revisable ADR-021 execution model. There is no reconciler loop and
 * no scheduler in this module; an ADR-021 runner drives this engine (tests drive it directly).
 *
 * <p>The transaction re-reads the pipeline first and skips a non-RUNNING one, so a cancel that
 * committed beforehand is honored. The cancel-during-call race is then closed by the task's
 * {@code @Version} optimistic lock (see {@code docs/exception-strategy.md}).
 */
@Component
public class PipelineEngine {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskMachine machine;
    private final Clock clock;

    public PipelineEngine(PipelineRepository pipelines, TaskRepository tasks, TaskMachine machine, Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.machine = machine;
        this.clock = clock;
    }

    @Transactional
    public void advance(Long pipelineId) {
        Pipeline pipeline = pipelines.findById(pipelineId).orElse(null);
        if (pipeline == null || pipeline.getStatus() != PipelineStatus.RUNNING) {
            return;
        }
        List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipelineId);
        Task current = currentTask(chain);
        if (current != null && isDue(current)) {
            machine.advance(pipeline.getTarget(), current);
        }
        converge(pipelineId, chain);
    }

    private Task currentTask(List<Task> chain) {
        return chain.stream()
                .filter(task -> !task.getStatus().isTerminal())
                .findFirst()
                .orElse(null);
    }

    private boolean isDue(Task task) {
        return task.getNextCheckAt() == null || !task.getNextCheckAt().isAfter(clock.instant());
    }

    private void converge(Long pipelineId, List<Task> chain) {
        if (chain.stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
            cancelRemaining(chain);
            pipelines.finish(pipelineId, PipelineStatus.FAILED, clock.instant());
        } else if (chain.stream().allMatch(task -> task.getStatus() == TaskStatus.DONE)) {
            pipelines.finish(pipelineId, PipelineStatus.DONE, clock.instant());
        }
    }

    private void cancelRemaining(List<Task> chain) {
        Instant now = clock.instant();
        chain.stream()
                .filter(task -> !task.getStatus().isTerminal())
                .forEach(task -> {
                    task.setStatus(TaskStatus.CANCELLED);
                    task.setFinishedAt(now);
                    tasks.save(task);
                });
    }
}
