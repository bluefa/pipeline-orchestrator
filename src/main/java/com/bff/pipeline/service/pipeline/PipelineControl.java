package com.bff.pipeline.service.pipeline;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.task.TaskCanceller;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 취소 기능을 구현한다 (ADR-016 §7). 단일 트랜잭션 안에서 동기적이고 원자적으로 처리되며
 * — CANCELLING 중간 상태는 존재하지 않는다. 비종료 태스크(BLOCKED/READY/IN_PROGRESS)는 모두
 * CANCELLED로 수렴되고, 파이프라인도 CANCELLED로 수렴되며, {@code active_target}이 초기화되어
 * 해당 대상을 재사용할 수 있게 된다.
 *
 * <p>취소는 멱등적이다: 이미 종료 상태인 파이프라인은 변경 없이 그대로 반환된다. RUNNING 상태를
 * 전제로 하는 {@code finish()} CAS가 유일한 가드이다 — 이미 종료된 파이프라인(재취소)과
 * 수렴에서 먼저 완료된 경우 모두 0행을 건드리므로, 기존 종료 실행이 재활성화되지 않고 그대로
 * 반환된다. 오직 먼저 도달한 취소만이 태스크들을 종료 처리한다. 진행 중인 엔진 advance와 취소
 * 간의 경합은 태스크의 {@code @Version} 낙관적 잠금으로 봉쇄된다
 * ({@code docs/exception-strategy.md} 참조).
 */
@Service
public class PipelineControl {

    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final TaskCanceller taskCanceller;
    private final Clock clock;

    public PipelineControl(PipelineRepository pipelineRepository, TaskRepository taskRepository, TaskCanceller taskCanceller,
            Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.taskCanceller = taskCanceller;
        this.clock = clock;
    }

    @Transactional
    public Pipeline cancel(Long pipelineId) {
        if (!pipelineRepository.existsById(pipelineId)) {
            throw new IllegalArgumentException("no pipeline " + pipelineId);
        }
        if (pipelineRepository.finish(pipelineId, PipelineStatus.CANCELLED, clock.instant()) != 0) {
            taskCanceller.cancelNonTerminal(taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId));
        }
        return pipelineRepository.findById(pipelineId).orElseThrow();
    }
}
