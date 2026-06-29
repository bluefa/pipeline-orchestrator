package com.bff.pipeline.service;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 취소 기능을 구현한다 (ADR-021 Decision 6). 취소는 워커가 해당 파이프라인에 대한
 * 활성 리스(live lease)를 보유하고 있는지 여부에 따라 두 경우(Case A / Case B)로 분기된다.
 *
 * <p><b>Case A — 유휴 파이프라인 (클레임 없음 또는 리스 만료):</b> API 경로가 직접 파이프라인을
 * 종료 처리한다. {@code cancelIfIdle}이 단일 UPDATE에서 {@code status = CANCELLED},
 * {@code claimed_by = NULL}, {@code claimed_until = NULL}을 동시에 적용하므로, 만료된 리스를
 * 보유한 스트래글러(straggler) 워커의 tx2가 {@code claimed_by} 가드에서 no-op된다(부활 불가).
 * RUNNING 상태 + 유휴 조건에 가드되므로 재취소 또는 이미 종료된 파이프라인은 0행 no-op(멱등적).
 * Case A가 성공하면 비종료 태스크(BLOCKED/READY/IN_PROGRESS)를 모두 CANCELLED로 전환한다.
 *
 * <p><b>Case B — 활성 리스 보유 중 (라이브 워커가 실행 중):</b> API 경로는 {@code status}를
 * 쓸 수 없다(클레임 보유 워커가 유일한 상태 기록자). {@code requestCancel}이
 * {@code cancel_requested = true}와 {@code next_due_at = now}만 설정하여 파이프라인을 깨운다.
 * 워커는 안전 지점(클레임 직후, tx2 내부)에서 플래그를 읽고 스스로 CANCELLED를 적용한다.
 * Case A의 UPDATE가 0행을 반환할 때 Case B로 진입하며, 이미 종료 상태인 파이프라인은
 * {@code requestCancel}도 0행 no-op(RUNNING 상태 가드)이므로 멱등성이 유지된다.
 *
 * <p>어느 경우에도 {@code status} 가드는 불필요하다 — 각 케이스마다 단일 상태 기록자가 존재하며,
 * 취소와 워커 경합은 파이프라인 행 경쟁에서 먼저 커밋한 쪽이 이긴다(ADR-021 Decision 6).
 */
@Service
public class PipelineControl {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskCanceller taskCanceller;
    private final Clock clock;

    public PipelineControl(PipelineRepository pipelines, TaskRepository tasks, TaskCanceller taskCanceller,
            Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.taskCanceller = taskCanceller;
        this.clock = clock;
    }

    @Transactional
    public Pipeline cancel(Long pipelineId) {
        if (!pipelines.existsById(pipelineId)) {
            throw new IllegalArgumentException("no pipeline " + pipelineId);
        }
        Instant now = clock.instant();
        if (pipelines.cancelIfIdle(pipelineId, now) != 0) {
            taskCanceller.cancelNonTerminal(tasks.findByPipelineIdOrderBySequenceAsc(pipelineId));
        } else {
            pipelines.requestCancel(pipelineId, now);
        }
        return pipelines.findById(pipelineId).orElseThrow();
    }
}
