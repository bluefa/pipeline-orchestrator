package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
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
        IntStream.range(0, steps.size())
                .mapToObj(sequence -> {
                    Recipe.Step step = steps.get(sequence);
                    boolean first = sequence == 0;
                    return Task.builder()
                            .pipelineId(pipeline.getId())
                            .sequence(sequence)
                            .taskName(step.taskName())
                            .operation(step.operation())
                            .status(first ? TaskStatus.READY : TaskStatus.BLOCKED)
                            .readyAt(first ? now : null)
                            .failCount(0)
                            .build();
                })
                .forEach(tasks::save);
        return pipeline;
    }
}
