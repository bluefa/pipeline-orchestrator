package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 하나를 한 단계 전진시키는 도메인 연산으로, 독립된 커밋 트랜잭션 안에서 실행된다
 * (ADR-016 §1–§2). 현재 태스크(시퀀스가 가장 낮은 비종료 태스크)를 선택하고, 그것을 전진시킨 뒤,
 * 파이프라인을 수렴(converge)한다. 전진 방향의 상태 변이를 위한 유일한 진입점이다.
 *
 * <p>현재 태스크는 시퀀스가 가장 낮은 비종료 태스크이며, 시퀀스가 높은 태스크들은 그 뒤에서
 * BLOCKED 상태로 대기한다. 수렴 단계는 실행 결과를 확정한다: FAILED 태스크가 하나라도 존재하면
 * 파이프라인을 FAILED로 전환하고 나머지 비종료 태스크들을 CANCEL 처리한다 (ADR-016 §7);
 * 모든 태스크가 DONE이면 파이프라인을 DONE으로 전환한다. 두 경우 모두 RUNNING 상태를 전제로 하는
 * {@code finish()} CAS를 통해 종료되므로, 동시에 실행된 취소 요청이 파이프라인을 이미 CANCELLED로
 * 전환했다면 수렴이 그것을 덮어쓸 수 없다. {@code taskStateMachine.advance}는 인메모리 체인에서 현재
 * 관리 태스크를 직접 변경하므로 수렴 단계는 DB를 재조회하지 않고도 새 상태를 읽을 수 있다;
 * {@code finish()}의 flush가 그 변경을 영구적으로 반영한다.
 *
 * <p><b>실행 제어는 이 클래스의 범위 밖이다.</b> {@code advance}가 <em>언제</em>, <em>얼마나 자주</em>,
 * <em>어떤 동시성 수준으로</em> 호출되는지 — 러너, 스케줄링, 워커 풀, 장애 복구 — 는 별도로
 * 독립적으로 개정 가능한 ADR-021 실행 모델이 담당한다. 이 모듈에는 리컨사일러 루프나 스케줄러가
 * 없으며, ADR-021 러너가 이 엔진을 구동한다(테스트는 직접 구동한다).
 *
 * <p>트랜잭션은 파이프라인을 먼저 재조회하고 RUNNING 상태가 아닌 경우 no-op으로 처리하므로,
 * 이미 커밋된 취소 요청이 존재하면 이를 존중한다. 존재하지 않는 파이프라인 id가 전달되면
 * {@code IllegalArgumentException}을 던져 즉시 실패한다. 호출 중 발생하는 취소 경합은
 * 태스크의 {@code @Version} 낙관적 잠금으로 봉쇄된다
 * ({@code docs/exception-strategy.md} 참조).
 */
@Component
public class PipelineEngine {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskStateMachine taskStateMachine;
    private final TaskCanceller taskCanceller;
    private final Clock clock;

    public PipelineEngine(PipelineRepository pipelines, TaskRepository tasks, TaskStateMachine taskStateMachine,
            TaskCanceller taskCanceller, Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.taskStateMachine = taskStateMachine;
        this.taskCanceller = taskCanceller;
        this.clock = clock;
    }

    @Transactional
    public void advance(Long pipelineId) {
        Pipeline pipeline = pipelines.findById(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("no pipeline " + pipelineId));
        if (pipeline.getStatus().isTerminal()) { return; }
        List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipelineId);
        Optional<Task> current = currentTask(chain);
        current.filter(this::isDue).ifPresent(task -> taskStateMachine.advance(pipeline.getTarget(), task));
        converge(pipelineId, chain);
    }

    private Optional<Task> currentTask(List<Task> chain) {
        return chain.stream().filter(task -> !task.getStatus().isTerminal()).findFirst();
    }

    private boolean isDue(Task task) {
        return task.getNextCheckAt() == null || !task.getNextCheckAt().isAfter(clock.instant());
    }

    private void converge(Long pipelineId, List<Task> chain) {
        if (chain.stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
            taskCanceller.cancelNonTerminal(chain);
            pipelines.finish(pipelineId, PipelineStatus.FAILED, clock.instant());
        } else if (chain.stream().allMatch(task -> task.getStatus() == TaskStatus.DONE)) {
            pipelines.finish(pipelineId, PipelineStatus.DONE, clock.instant());
        }
    }
}
