package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.PipelinePlan;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 실행 하나를 단일 트랜잭션 안에서 삽입한다 — active_target이 설정된 파이프라인과 task 체인을 함께 만든다.
 * 첫 task는 READY로 출발하고 나머지는 BLOCKED로 두었다가, 선행 task가 끝날 때마다 하나씩 언블로킹한다(ADR-016 §2).
 * provider와 recipe는 트랜잭션 밖(PipelineCreator)에서 조회·선택해 넘겨받는다 — 외부 호출이 행 락을 쥐지 않게 한다.
 *
 * 각 task 행에는 진실원인 task_definition 이름과, 거기서 파생한 실행 투영(taskName/operation)·slot flag 캐시를 채운다
 * (설계 §4).
 */
@Component
public class PipelineInserter {

    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final Clock clock;

    public PipelineInserter(PipelineRepository pipelineRepository, TaskRepository taskRepository, Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    @Transactional
    public Pipeline insert(PipelinePlan plan) {
        Instant now = clock.instant();
        String target = plan.target();
        RecipeDefinition recipe = plan.recipe();
        Pipeline pipeline = pipelineRepository.saveAndFlush(Pipeline.builder()
                .type(recipe.pipelineType())
                .target(target)
                .cloudProvider(recipe.provider())
                .recipeDefinition(recipe.name())
                .status(PipelineStatus.RUNNING)
                .activeTarget(target)
                .createdAt(now)
                .lastActivityAt(now)
                .nextDueAt(now)            // ADR-021 Decision 4: 생성 즉시 claim되도록 시딩한다(비워 두면 영영 unclaimed로 남는다)
                .cancelRequested(false)
                .build());
        taskRepository.saveAll(buildChain(pipeline.getId(), recipe.steps(), now));
        return pipeline;
    }

    private List<Task> buildChain(Long pipelineId, List<TaskDefinition> steps, Instant now) {
        return IntStream.range(0, steps.size())
                .mapToObj(sequence -> {
                    TaskDefinition definition = steps.get(sequence);
                    boolean first = sequence == 0;
                    return Task.builder()
                            .pipelineId(pipelineId)
                            .sequence(sequence)
                            .taskName(definition.mechanism())
                            .operation(definition.operation())
                            .taskDefinition(definition.name())
                            .consumesTerraformSlot(definition.consumesTerraformSlot())
                            .status(first ? TaskStatus.READY : TaskStatus.BLOCKED)
                            .readyAt(first ? now : null)
                            .failCount(0)
                            .build();
                })
                .toList();
    }
}
