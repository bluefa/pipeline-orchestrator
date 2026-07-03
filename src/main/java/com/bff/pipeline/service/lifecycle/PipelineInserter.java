package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.PipelinePlan;
import com.bff.pipeline.model.PipelinePlan.PlannedStep;
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
 * type·provider·recipe 분류·step 목록은 트랜잭션 밖(PipelineCreator)에서 조회·검증해 {@link PipelinePlan}으로
 * 넘겨받는다 — 외부 호출이 행 락을 쥐지 않게 한다. 카탈로그 recipe든 custom 요청이든 여기서는 같은 꼴의 plan을 삽입한다.
 *
 * 각 task 행에는 진실원인 task_definition 이름과, 거기서 파생한 실행 투영(taskName/operation)·slot flag 캐시를 채운다
 * (설계 §4). custom 요청은 step별 운영자 설명(description)도 함께 저장한다.
 *
 * 생성 시 초기 상태와 nextDueAt은 startDelay로 갈린다(LIN-17/LIN-30). startDelay가 양수면 {@code status=PENDING,
 * nextDueAt=now+startDelay}로 시딩한다 — 그 시각 전까지 claim 술어 {@code next_due_at <= now}에 안 걸려 첫 Task가
 * dispatch되지 않고, 그 사이 취소는 claim 없는 Case A로 즉시 처리된다. startDelay가 0이면 fast path로
 * {@code status=RUNNING, nextDueAt=now}라 생성 즉시 claim되고 PENDING을 거치지 않는다(전이 1회 절약, ADR-021
 * Decision 4). {@code PENDING → RUNNING} 전이는 첫 claim 트랜잭션이 수행한다({@code PipelineClaimer}).
 */
@Component
public class PipelineInserter {

    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public PipelineInserter(PipelineRepository pipelineRepository, TaskRepository taskRepository,
            PipelineSettings pipelineSettings, Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.pipelineSettings = pipelineSettings;
        this.clock = clock;
    }

    @Transactional
    public Pipeline insert(PipelinePlan plan) {
        Instant now = clock.instant();
        String target = plan.target();
        boolean delayed = pipelineSettings.startDelay().isPositive();   // LIN-30: 지연 창이 있으면 PENDING, 없으면 fast path RUNNING
        Pipeline pipeline = pipelineRepository.saveAndFlush(Pipeline.builder()
                .type(plan.type())
                .target(target)
                .cloudProvider(plan.provider())
                .recipeDefinition(plan.recipeDefinition())
                .status(delayed ? PipelineStatus.PENDING : PipelineStatus.RUNNING)
                .activeTarget(target)
                .createdAt(now)
                .lastActivityAt(now)
                .nextDueAt(delayed ? now.plus(pipelineSettings.startDelay()) : now)   // LIN-17: 시작 지연을 스케줄링으로 반영(sleep 금지)
                .cancelRequested(false)
                .build());
        taskRepository.saveAll(buildChain(pipeline.getId(), plan.steps(), now));
        return pipeline;
    }

    private List<Task> buildChain(Long pipelineId, List<PlannedStep> steps, Instant now) {
        return IntStream.range(0, steps.size())
                .mapToObj(sequence -> {
                    PlannedStep step = steps.get(sequence);
                    boolean first = sequence == 0;
                    return Task.builder()
                            .pipelineId(pipelineId)
                            .sequence(sequence)
                            .taskName(step.definition().mechanism())
                            .operation(step.definition().operation())
                            .taskDefinition(step.definition().name())
                            .consumesTerraformSlot(step.definition().consumesTerraformSlot())
                            .description(step.description())
                            .status(first ? TaskStatus.READY : TaskStatus.BLOCKED)
                            .readyAt(first ? now : null)
                            .failCount(0)
                            .build();
                })
                .toList();
    }
}
