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
 * ADR-021 phase-B: tx2 가드 라이트백(guarded write-back, ADR Decision 4). 파이프라인 행을
 * 비관적 쓰기 잠금({@code FOR UPDATE})으로 조회하고, 클레임 소유권(매칭 토큰 AND 유효 리스,
 * matching token AND live lease)을 검증한 뒤 {@link StepOutcome}을 관리 엔티티에 적용한다.
 *
 * <p>소유권 검증 실패(스테일 스트래글러 또는 만료 리스 스트래글러)는 전체 report를 no-op으로 처리한다.
 * 리스 만료 스트래글러는 토큰이 여전히 일치하더라도 리스가 만료되면 쓰기가 거부된다(ADR-021 Decision 5에
 * 따라 리스를 충분히 크게 설정하면 정상 운영에서 tx2는 유효 리스 안에서 실행된다;
 * {@link com.bff.pipeline.ExecutionSettings}는 최소 조건인 {@code leaseDuration > apiCallTimeout}만
 * 강제하며, 병리적 리스 초과 시에는 report가 no-op으로 처리되고 재디스패치로 안전하게 회수된다(멱등)).
 * {@code cancelRequested}는
 * 잠금을 획득한 직후 확인되므로 협력적 취소 플래그가 경합 없이 관찰된다(ADR Decision 6).
 * 관리 엔티티 필드 직접 설정 + dirty-check flush — CAS 없음
 * (ADR Decision 4/6: FOR UPDATE + 검증된 단일 쓰기자이므로 별도 상태 가드 불필요).
 *
 * <p>현재 태스크가 DONE이 되면, {@code converge} 직후 같은 tx2 안에서 다음 BLOCKED 후속 태스크를
 * READY로 승격한다(ADR Decision 4 §152: "same tx2 flips the next task BLOCKED → READY").
 * 이로써 별도 클레임 사이클 없이 동일 트랜잭션에서 후속 태스크가 READY 상태로 커밋된다.
 *
 * <p>외부 호출(phase-A)은 이 클래스와 별개의 tx1~tx2 사이에서 실행된다.
 */
@Component
public class StepReporter {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskMachine machine;
    private final TaskCanceller taskCanceller;
    private final Clock clock;

    public StepReporter(PipelineRepository pipelines, TaskRepository tasks, TaskMachine machine,
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
        if (pipeline == null || !ownsLiveClaim(pipeline, claimToken)) return;
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
    public void reportCancel(long pipelineId, String claimToken) {
        Pipeline pipeline = pipelines.findByIdForUpdate(pipelineId).orElse(null);
        if (pipeline == null || !ownsLiveClaim(pipeline, claimToken)) return;
        cancel(pipeline, tasks.findByPipelineIdOrderBySequenceAsc(pipelineId));
    }

    @Transactional
    public void reschedule(long pipelineId, String claimToken, Duration delay) {
        Pipeline pipeline = pipelines.findByIdForUpdate(pipelineId).orElse(null);
        if (pipeline == null || !ownsLiveClaim(pipeline, claimToken)) return;
        if (pipeline.isCancelRequested()) {
            cancel(pipeline, tasks.findByPipelineIdOrderBySequenceAsc(pipelineId));
            return;
        }
        pipeline.setNextDueAt(clock.instant().plus(delay));
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
    }

    /**
     * 소유권 검증: 매칭 토큰 AND 유효 리스(matching token AND live lease).
     * 만료 리스 스트래글러는 토큰이 일치하더라도 쓰기가 거부된다(ADR Decision 5 펜싱 전제).
     */
    private boolean ownsLiveClaim(Pipeline pipeline, String claimToken) {
        return claimToken.equals(pipeline.getClaimedBy())
                && pipeline.getClaimedUntil() != null
                && pipeline.getClaimedUntil().isAfter(clock.instant());
    }

    private void cancel(Pipeline pipeline, List<Task> chain) {
        taskCanceller.cancelNonTerminal(chain);
        pipeline.setStatus(PipelineStatus.CANCELLED);
        pipeline.setActiveTarget(null);
        pipeline.setLastActivityAt(clock.instant());
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
    }

    private void converge(Pipeline pipeline, List<Task> chain) {
        Instant now = clock.instant();
        if (chain.stream().anyMatch(t -> t.getStatus() == TaskStatus.FAILED)) {
            taskCanceller.cancelNonTerminal(chain);
            terminalize(pipeline, PipelineStatus.FAILED, now);
        } else if (chain.stream().allMatch(t -> t.getStatus() == TaskStatus.DONE)) {
            terminalize(pipeline, PipelineStatus.DONE, now);
        } else if (chain.stream().allMatch(t -> t.getStatus().isTerminal())) {
            // 방어적 생존성 가드: 모든 태스크가 종료 상태이나 all-DONE·any-FAILED가 아닌 경우
            // (예: 전체 CANCELLED 체인) — RUNNING 파이프라인이 재클레임 루프에 갇히는 것을 방지한다.
            terminalize(pipeline, PipelineStatus.CANCELLED, now);
        }
    }

    private void terminalize(Pipeline pipeline, PipelineStatus status, Instant now) {
        pipeline.setStatus(status);
        pipeline.setActiveTarget(null);
        pipeline.setLastActivityAt(now);
    }

    private void releaseClaim(Pipeline pipeline, List<Task> chain) {
        pipeline.setClaimedBy(null);
        pipeline.setClaimedUntil(null);
        if (pipeline.getStatus() == PipelineStatus.RUNNING) {
            pipeline.setNextDueAt(currentTask(chain)
                    .map(Task::getNextCheckAt)
                    .map(at -> at == null ? clock.instant() : at)
                    .orElse(clock.instant()));
        }
    }

    /**
     * ADR-021 Decision 4 §152: 현재 태스크가 DONE으로 전환된 직후, 같은 tx2 안에서 다음 BLOCKED
     * 후속 태스크를 READY로 승격한다. 파이프라인이 RUNNING 상태일 때만 동작하며, 후속 태스크가
     * BLOCKED가 아니면(이미 READY/IN_PROGRESS이거나 FAILED/converge 경로) 아무것도 하지 않는다.
     *
     * <p>이 단계는 별도의 클레임 사이클을 없애 트랜잭션 중간 불변식 위반(RUNNING 파이프라인에
     * READY/IN_PROGRESS 현재 태스크가 없는 상태)을 제거한다. {@link StepRunner}의
     * {@code case BLOCKED -> StepOutcome.unblock()} 분기는 불변식 복구 안전망으로 유지된다.
     */
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

    private Optional<Task> currentTask(List<Task> chain) {
        return chain.stream().filter(t -> !t.getStatus().isTerminal()).findFirst();
    }
}
