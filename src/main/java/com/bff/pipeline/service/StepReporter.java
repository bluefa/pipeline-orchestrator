package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.StepOutcome;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-021 phase-B: tx2 가드 라이트백(Decision 4). pipeline 행을 {@code FOR UPDATE}로 잠그고 claim 소유권
 * (매칭 토큰 AND live lease)을 검증한 뒤, phase-A({@link StepRunner})가 계산한 {@link StepOutcome}을
 * 관리 태스크에 적용한다. 외부 호출은 이 클래스에 없다.
 *
 * <p>tx2 고정 순서: (1) {@code findByIdForUpdate}로 행 잠금; (2) {@link #ownsClaim} 검증(토큰 일치) — 실패 시
 * 전체 no-op(stale straggler 차단); (3) {@code cancel_requested}를 같은 락 아래 읽기 — set이면
 * 협력적 취소를 경합 없이 관찰; (4) outcome 적용 + converge + BLOCKED 후속 승격; (5) claim 해제 + next_due_at 전진.
 * CAS/`status=:expected` 가드가 없다 — {@code FOR UPDATE} + 검증된 단일 쓰기자이므로 불필요(Decision 4/6).
 *
 * <p>현재 태스크가 DONE이 되면 같은 tx2 안에서 다음 BLOCKED 후속 태스크를 READY로 승격해
 * (Decision 4 §152) 별도 claim 사이클 없이 트랜잭션 중간 불변식 위반을 제거한다.
 */
@Component
public class StepReporter {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskStateMachine machine;
    private final TaskCanceller taskCanceller;
    private final Clock clock;

    public StepReporter(PipelineRepository pipelines, TaskRepository tasks, TaskStateMachine machine,
            TaskCanceller taskCanceller, Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.machine = machine;
        this.taskCanceller = taskCanceller;
        this.clock = clock;
    }

    @Transactional
    public void report(long pipelineId, String claimToken, StepOutcome outcome) {
        Pipeline pipeline = pipelines.findByIdForUpdate(pipelineId).orElse(null);
        if (pipeline == null || !ownsClaim(pipeline, claimToken)) return;
        List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipelineId);
        if (pipeline.isCancelRequested()) { cancel(pipeline, chain); return; }
        if (outcome != null) {
            currentTask(chain).ifPresent(task -> machine.applyOutcome(task, outcome));
        }
        converge(pipeline, chain);
        promoteBlockedSuccessor(pipeline, chain);
        releaseClaim(pipeline, chain);
    }

    @Transactional
    public void reschedule(long pipelineId, String claimToken, Duration delay) {
        Pipeline pipeline = pipelines.findByIdForUpdate(pipelineId).orElse(null);
        if (pipeline == null || !ownsClaim(pipeline, claimToken)) return;
        if (pipeline.isCancelRequested()) {
            cancel(pipeline, tasks.findByPipelineIdOrderBySequenceAsc(pipelineId));
            return;
        }
        pipeline.setNextDueAt(clock.instant().plus(delay));
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
    }

    /**
     * 소유권: 토큰 일치(ADR-021 Decision 4 — {@code WHERE claimed_by = :claim_token}). fencing은 토큰만으로
     * 충분하다 — 재claim은 새 토큰을 발급하고 cancel(Case A)은 토큰을 지우므로, 위험한 두 경합 모두 토큰 불일치로
     * no-op된다. lease 만료만으로는 거부하지 않는다(아무도 reclaim/cancel하지 않았다면 stale-claim 보유자가 여전히
     * 정당한 단일 소유자이며, 그 report를 버리면 불필요한 재dispatch만 유발).
     */
    private boolean ownsClaim(Pipeline pipeline, String claimToken) {
        return claimToken != null && claimToken.equals(pipeline.getClaimedBy());
    }

    private void cancel(Pipeline pipeline, List<Task> chain) {
        taskCanceller.cancelNonTerminal(chain);
        terminalize(pipeline, PipelineStatus.CANCELLED, clock.instant());
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
    }

    private void converge(Pipeline pipeline, List<Task> chain) {
        Instant now = clock.instant();
        if (chain.stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
            taskCanceller.cancelNonTerminal(chain);
            terminalize(pipeline, PipelineStatus.FAILED, now);
        } else if (chain.stream().allMatch(task -> task.getStatus() == TaskStatus.DONE)) {
            terminalize(pipeline, PipelineStatus.DONE, now);
        } else if (chain.stream().allMatch(task -> task.getStatus().isTerminal())) {
            // 방어적 생존성 가드: 모든 task가 종료이나 all-DONE·any-FAILED가 아닌 경우(예: 전체 CANCELLED)
            // — RUNNING pipeline이 재claim 루프에 갇히는 것을 방지한다.
            terminalize(pipeline, PipelineStatus.CANCELLED, now);
        }
    }

    private void terminalize(Pipeline pipeline, PipelineStatus status, Instant now) {
        pipeline.setStatus(status);
        pipeline.setActiveTarget(null);
        pipeline.setLastActivityAt(now);
    }

    private void promoteBlockedSuccessor(Pipeline pipeline, List<Task> chain) {
        if (pipeline.getStatus() != PipelineStatus.RUNNING) return;
        currentTask(chain).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.BLOCKED) {
                task.setStatus(TaskStatus.READY);
                task.setReadyAt(clock.instant());
                tasks.save(task);
            }
        });
    }

    private void releaseClaim(Pipeline pipeline, List<Task> chain) {
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
        if (pipeline.getStatus() == PipelineStatus.RUNNING) {
            // Optional.map(getNextCheckAt)는 nextCheckAt이 null이면 empty가 되므로 orElseGet이 now로 메운다.
            pipeline.setNextDueAt(currentTask(chain).map(Task::getNextCheckAt).orElseGet(clock::instant));
        }
    }

    private Optional<Task> currentTask(List<Task> chain) {
        return chain.stream().filter(task -> !task.getStatus().isTerminal()).findFirst();
    }
}
