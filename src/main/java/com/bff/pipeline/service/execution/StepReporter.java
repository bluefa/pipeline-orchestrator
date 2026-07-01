package com.bff.pipeline.service.execution;
import com.bff.pipeline.service.task.TaskCanceller;
import com.bff.pipeline.service.task.TaskStateMachine;

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
 * write-back 단계 — write-back 트랜잭션의 가드 라이트백이다(Decision 4). pipeline 행을 {@code FOR UPDATE}로 잠그고 claim 소유권을
 * 검증한 뒤(token-only: {@code claimed_by} 토큰 일치), run 단계({@link StepRunner})가 계산한 {@link StepOutcome}을
 * 태스크에 적용한다. 외부 호출은 이 클래스에 없다.
 *
 * <p>write-back 트랜잭션은 순서가 고정돼 있다. (1) {@code findByIdForUpdate}로 행을 잠근다; (2) {@link #ownsClaim}으로 토큰 일치를
 * 검증한다 — 실패하면 전체 no-op으로 stale straggler를 막는다; (3) 같은 락 아래에서 {@code cancel_requested}를
 * 읽어 협력적 취소를 경합 없이 관찰한다; (4) outcome을 적용하고 converge한 뒤 BLOCKED 후속을 승격한다;
 * (5) claim을 해제하고 next_due_at을 전진시킨다. CAS나 {@code status=:expected} 가드는 없다 —
 * {@code FOR UPDATE}에 검증된 단일 쓰기자가 더해지므로 필요 없다(Decision 4/6).
 *
 * <p>현재 태스크가 DONE이 되면 같은 write-back 트랜잭션 안에서 다음 BLOCKED 후속 태스크를 READY로 승격한다(Decision 4 §152).
 * 별도 claim 사이클을 거치지 않으니 트랜잭션 중간에 불변식이 깨지는 상태가 남지 않는다.
 */
@Component
public class StepReporter {

    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final TaskStateMachine taskStateMachine;
    private final TaskCanceller taskCanceller;
    private final Clock clock;

    public StepReporter(PipelineRepository pipelineRepository, TaskRepository taskRepository, TaskStateMachine taskStateMachine,
            TaskCanceller taskCanceller, Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.taskStateMachine = taskStateMachine;
        this.taskCanceller = taskCanceller;
        this.clock = clock;
    }

    @Transactional
    public void writeBack(long pipelineId, String claimToken, StepOutcome outcome) {
        claimedPipeline(pipelineId, claimToken).ifPresent(pipeline -> {
            List<Task> chain = taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId);
            if (pipeline.isCancelRequested()) { cancel(pipeline, chain); return; }
            if (outcome != null) {
                currentTask(chain).ifPresent(task -> taskStateMachine.applyOutcome(task, outcome));
            }
            converge(pipeline, chain);
            promoteBlockedSuccessor(pipeline, chain);
            releaseClaim(pipeline, chain);
        });
    }

    @Transactional
    public void reschedule(long pipelineId, String claimToken, Duration delay) {
        claimedPipeline(pipelineId, claimToken).ifPresent(pipeline -> {
            if (pipeline.isCancelRequested()) {
                cancel(pipeline, taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId));
                return;
            }
            pipeline.setNextDueAt(clock.instant().plus(delay));
            pipeline.setClaimedBy(null);
            pipeline.setClaimedUntil(null);
        });
    }

    /** 행을 FOR UPDATE로 잠근 뒤 토큰이 일치할 때만 돌려준다(소유권 확인). 불일치하거나 없으면 empty라 전체가 no-op된다. */
    private Optional<Pipeline> claimedPipeline(long pipelineId, String claimToken) {
        return pipelineRepository.findByIdForUpdate(pipelineId)
                .filter(pipeline -> ownsClaim(pipeline, claimToken));
    }

    /**
     * 소유권은 토큰 일치로 판정한다(Decision 4 — {@code WHERE claimed_by = :claim_token}). fencing에는 토큰만으로
     * 충분하다. 재claim은 새 토큰을 발급하고 cancel(Case A)은 토큰을 지우므로, 위험한 두 경합 모두 토큰 불일치로
     * no-op되기 때문이다. lease 만료만으로는 거부하지 않는다 — 아무도 reclaim이나 cancel을 하지 않았다면
     * stale-claim 보유자가 여전히 정당한 단일 소유자이고, 그 report를 버리면 불필요한 재dispatch만 생긴다.
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
        if (anyTaskFailed(chain)) {
            taskCanceller.cancelNonTerminal(chain);
            terminalize(pipeline, PipelineStatus.FAILED, now);
        } else if (allTasksDone(chain)) {
            terminalize(pipeline, PipelineStatus.DONE, now);
        } else if (allTasksTerminal(chain)) {
            // 방어적 생존성 가드: 모든 task가 종료됐지만 all-DONE도 any-FAILED도 아닌 경우다(예: 전체 CANCELLED).
            // RUNNING pipeline이 재claim 루프에 갇히지 않게 막는다.
            terminalize(pipeline, PipelineStatus.CANCELLED, now);
        }
    }

    private boolean anyTaskFailed(List<Task> chain) {
        return chain.stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED);
    }

    private boolean allTasksDone(List<Task> chain) {
        return chain.stream().allMatch(task -> task.getStatus() == TaskStatus.DONE);
    }

    private boolean allTasksTerminal(List<Task> chain) {
        return chain.stream().allMatch(task -> task.getStatus().isTerminal());
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
                taskRepository.save(task);
            }
        });
    }

    private void releaseClaim(Pipeline pipeline, List<Task> chain) {
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
        if (pipeline.getStatus() == PipelineStatus.RUNNING) {
            // nextCheckAt이 null이면 map 결과가 empty가 되므로, orElseGet이 now로 메운다.
            pipeline.setNextDueAt(currentTask(chain).map(Task::getNextCheckAt).orElseGet(clock::instant));
        }
    }

    private Optional<Task> currentTask(List<Task> chain) {
        return chain.stream().filter(task -> !task.getStatus().isTerminal()).findFirst();
    }
}
