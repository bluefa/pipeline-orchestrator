package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.RecipeStep;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 실행 하나를 단일 트랜잭션 안에 삽입한다 — {@code active_target}이 설정된 파이프라인과
 * 태스크 체인을 함께 생성한다. 첫 번째 태스크는 READY 상태로 시작하고, 나머지는 BLOCKED 상태로
 * 시작하여 선행 태스크가 완료될 때마다 하나씩 언블로킹된다 (ADR-016 §2).
 *
 * <p>파이프라인에 대해 {@code saveAndFlush}를 사용하면 {@code active_target} 유니크 제약 위반이
 * 지연된 커밋이 아닌 이 지점에서 즉시 표면화되므로, {@link PipelineCreator}가 이를 캐치하여
 * 복구할 수 있다.
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
                .nextDueAt(now)
                .cancelRequested(false)
                .build());
        tasks.saveAll(buildChain(pipeline.getId(), recipes.forType(type).steps(), now));
        return pipeline;
    }

    private List<Task> buildChain(Long pipelineId, List<RecipeStep> steps, Instant now) {
        return IntStream.range(0, steps.size())
                .mapToObj(sequence -> {
                    RecipeStep step = steps.get(sequence);
                    boolean first = sequence == 0;
                    return Task.builder()
                            .pipelineId(pipelineId)
                            .sequence(sequence)
                            .taskName(step.taskName())
                            .operation(step.operation())
                            .status(first ? TaskStatus.READY : TaskStatus.BLOCKED)
                            .readyAt(first ? now : null)
                            .failCount(0)
                            .build();
                })
                .toList();
    }
}
