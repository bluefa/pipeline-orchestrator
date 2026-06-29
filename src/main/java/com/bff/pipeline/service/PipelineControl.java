package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin cancel (ADR-016 §7). Synchronous and atomic in one transaction — there is no CANCELLING
 * state. Every non-terminal task (BLOCKED/READY/IN_PROGRESS) converges to CANCELLED, the pipeline
 * converges to CANCELLED, and {@code active_target} is cleared so the target is reusable.
 *
 * <p>Cancel is idempotent: an already-terminal pipeline is returned unchanged. The RUNNING-guarded
 * {@code finish()} CAS is the sole guard — an already-terminal pipeline (re-cancel) and a converge that
 * won the race both touch 0 rows, so the existing terminal run is returned rather than resurrected, and
 * only the winning cancel terminalizes the tasks. The cancel race with an in-flight engine advance is
 * closed by the task's {@code @Version} optimistic lock — see {@code docs/exception-strategy.md}.
 */
@Service
public class PipelineControl {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final Clock clock;

    public PipelineControl(PipelineRepository pipelines, TaskRepository tasks, Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public Pipeline cancel(Long pipelineId) {
        if (!pipelines.existsById(pipelineId)) {
            throw new IllegalArgumentException("no pipeline " + pipelineId);
        }

        Instant now = clock.instant();
        if (pipelines.finish(pipelineId, PipelineStatus.CANCELLED, now) == 0) {
            return pipelines.findById(pipelineId).orElseThrow();
        }
        tasks.findByPipelineIdOrderBySequenceAsc(pipelineId).stream()
                .filter(task -> !task.getStatus().isTerminal())
                .forEach(task -> {
                    task.setStatus(TaskStatus.CANCELLED);
                    task.setFinishedAt(now);
                    tasks.save(task);
                });
        return pipelines.findById(pipelineId).orElseThrow();
    }
}
