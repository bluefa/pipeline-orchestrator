package com.bff.pipeline.create;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.PipelineType;
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
 * Inserts one new run — the pipeline (with {@code active_target} set) and its task chain — in a
 * single transaction. The first task starts READY; the rest start BLOCKED and are unblocked one at
 * a time as predecessors finish (ADR-016 §2).
 *
 * <p>{@code saveAndFlush} on the pipeline forces the {@code active_target} unique violation to
 * surface here (not at deferred commit), so {@link PipelineCreator} can catch and recover it.
 */
@Component
public class PipelineInserter {

    private final Recipes recipes;
    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final Clock clock;

    public PipelineInserter(Recipes recipes, PipelineRepository pipelines, TaskRepository tasks, Clock clock) {
        this.recipes = recipes;
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public Pipeline insert(String target, PipelineType type) {
        Instant now = clock.instant();
        Pipeline pipeline = pipelines.saveAndFlush(Pipeline.builder()
                .type(type)
                .target(target)
                .status(PipelineStatus.RUNNING)
                .activeTarget(target)
                .createdAt(now)
                .lastActivityAt(now)
                .build());

        List<Recipe.Step> steps = recipes.forType(type).steps();
        for (int seq = 0; seq < steps.size(); seq++) {
            Recipe.Step step = steps.get(seq);
            boolean first = seq == 0;
            tasks.save(Task.builder()
                    .pipelineId(pipeline.getId())
                    .seq(seq)
                    .kind(step.kind())
                    .operation(step.operation())
                    .status(first ? TaskStatus.READY : TaskStatus.BLOCKED)
                    .readyAt(first ? now : null)
                    .failCount(0)
                    .build());
        }
        return pipeline;
    }
}
