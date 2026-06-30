package com.bff.pipeline.service.lifecycle;
import com.bff.pipeline.service.task.TaskCanceller;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 취소(ADR-016 §7 + ADR-021 Decision 6)를 구현한다. Admin/API path(워커와 별개 프로세스)가 자기
 * 짧은 트랜잭션에서 호출한다. 한 가지 사실로 분기한다 — <b>지금 워커가 이 pipeline을 돌리는가?</b>
 *
 * <ul>
 *   <li><b>Case A</b>(claim 없음 또는 lease 만료): {@code cancelIfIdle} 단일 UPDATE가 직접 CANCELLED를 쓰고
 *       claim을 지운다(가드 {@code status='RUNNING' AND (claimed_by IS NULL OR claimed_until<now)}). 1행이 갱신되면
 *       비종료 task를 모두 CANCELLED로 수렴한다. 만료-lease straggler는 token이 지워져 tx2 소유권 가드에서 no-op된다.</li>
 *   <li><b>Case B</b>(live lease, {@code cancelIfIdle}가 0행): {@code requestCancel}이 {@code cancel_requested=true,
 *       next_due_at=now}만 쓴다. status는 건드리지 않는다 — claim 보유 워커가 안전지점에서 플래그를 읽어 스스로
 *       CANCELLED를 적용하는 유일한 status writer이다. {@code next_due_at=now}는 잠자는 pipeline을 깨운다.</li>
 * </ul>
 *
 * <p>claim과 Case A가 같은 pipeline 행을 두고 경합하면 먼저 커밋한 쪽이 이긴다: 워커가 claim한 뒤엔 Case A가 0행이
 * 되어 Case B로 폴백한다. 두 경로 모두 {@code status='RUNNING'} 가드로 종료 행을 보호하므로(멱등 재취소도 0행),
 * 어느 경우도 워커 tx2와 live token을 공유하지 않아 terminal resurrection이 불가능하다 — 별도 {@code status} 가드 불필요.
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
        if (pipelines.cancelIfIdle(pipelineId, now) != 0) {                       // Case A
            taskCanceller.cancelNonTerminal(tasks.findByPipelineIdOrderBySequenceAsc(pipelineId));
        } else {
            pipelines.requestCancel(pipelineId, now);                             // Case B (live lease, or already terminal → 0행 멱등)
        }
        return pipelines.findById(pipelineId).orElseThrow();
    }
}
