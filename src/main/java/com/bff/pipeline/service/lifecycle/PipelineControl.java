package com.bff.pipeline.service.lifecycle;
import com.bff.pipeline.service.task.TaskCanceller;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.exception.MissingPipelineIdException;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 취소(ADR-016 §7 + ADR-021 Decision 6)를 구현한다. 워커와 별개 프로세스인 Admin/API path가 짧은
 * 트랜잭션 안에서 호출한다. 분기 기준은 딱 하나다 — <b>지금 워커가 이 pipeline을 돌리고 있는가?</b>
 *
 * <ul>
 *   <li><b>Case A</b>(claim이 없거나 lease가 만료됨): {@code cancelIfIdle} UPDATE 한 방으로 CANCELLED를 쓰고 claim을
 *       지운다(가드 {@code status IN ('RUNNING','PENDING') AND (claimed_by IS NULL OR claimed_until<now)}). PENDING(시작
 *       지연 대기)은 claim이 없어 언제나 이 경로로 즉시 취소된다(LIN-30). 한 행이라도 갱신되면
 *       비종료 task를 모두 CANCELLED로 수렴시킨다. lease가 만료된 straggler는 token이 지워져 write-back 트랜잭션의 소유권 가드에서 no-op이 된다.</li>
 *   <li><b>Case B</b>(live lease, {@code cancelIfIdle}가 0행): {@code requestCancel}이 {@code cancel_requested=true,
 *       next_due_at=now}만 쓰고 status는 건드리지 않는다. claim을 쥔 워커가 안전지점에서 이 플래그를 읽어 직접
 *       CANCELLED를 적용하는 유일한 status writer이기 때문이다. {@code next_due_at=now}는 잠자던 pipeline을 깨운다.</li>
 * </ul>
 *
 * <p>claim과 Case A가 같은 pipeline 행을 두고 경합하면 먼저 커밋한 쪽이 이긴다. 워커가 claim을 마치면 PENDING은 이미
 * RUNNING+live lease로 전이돼 Case A는 0행이 되고 Case B로 폴백한다. Case A는 {@code IN ('RUNNING','PENDING')},
 * Case B는 {@code 'RUNNING'} 가드로 각각 종료된 행을 배제하므로(멱등 재취소도 0행), 어느 쪽도 워커의 write-back
 * 트랜잭션과 live token을 공유하지 않는다. 따라서 terminal resurrection이 불가능하고, 별도 {@code status} 가드가 필요 없다.
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
        if (pipelineId == null) {
            throw new MissingPipelineIdException();
        }
        if (!pipelineRepository.existsById(pipelineId)) {
            throw new PipelineNotFoundException(pipelineId);
        }
        Instant now = clock.instant();
        if (pipelineRepository.cancelIfIdle(pipelineId, now) != 0) {                       // Case A
            taskCanceller.cancelNonTerminal(taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId));
        } else {
            pipelineRepository.requestCancel(pipelineId, now);                             // Case B (live lease, 또는 이미 종료됨 → 0행 멱등)
        }
        return pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
    }
}
