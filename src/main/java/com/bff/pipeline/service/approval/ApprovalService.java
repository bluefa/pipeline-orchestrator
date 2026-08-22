package com.bff.pipeline.service.approval;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.enums.ApprovalChannel;
import com.bff.pipeline.enums.ApprovalStatus;
import com.bff.pipeline.exception.ApprovalDeadlinePassedException;
import com.bff.pipeline.exception.ApproverRequiredException;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.TaskNotAwaitingApprovalException;
import com.bff.pipeline.exception.TaskNotFoundException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskApprovalRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인·반려 결정을 기록하는 단 하나의 진입점이다(승인 게이트 ADR §결정 4). 콘솔에서 오든 Slack에서 오든
 * 같은 자리를 지나므로, 멱등·감사·경합 규칙을 한 곳에서만 지키면 된다.
 *
 * 여기서 하는 일은 두 가지뿐이다 — 결정을 승인 행에 남기고, 잠자던 파이프라인을 깨운다. 태스크와
 * 파이프라인의 상태는 건드리지 않는다. 상태를 쓰는 것은 파이프라인을 잡은 워커 하나뿐이라는 규칙이
 * 있고, 그 규칙 덕분에 "누가 상태를 바꿨는지 모르겠는" 상황이 생기지 않기 때문이다. 깨우고 나면 다음
 * 사이클에서 워커가 이 결정을 보고 태스크를 옮긴다.
 *
 * 반려만 예외적으로 한 가지를 더 한다 — 파이프라인에 취소 요청 플래그를 세운다. 이것은 상태 전이가
 * 아니라 기존 취소 API도 세우는 요청 표시이며, 워커가 그 표시를 보고 실행 전체를 취소로 닫는다. 반려를
 * 위해 별도의 취소 경로를 새로 만들지 않는 이유다.
 *
 * 잠금 순서는 파이프라인 먼저다(불변식 6). 워커의 마무리 트랜잭션이 파이프라인 행을 먼저 잠근 뒤
 * 승인 행을 만지므로, 여기서 반대로 잡으면 만료 시각 부근에 두 트랜잭션이 서로의 잠금을 기다리는 교착이
 * 생긴다. 그러면 워커 쪽은 작업이 유실되고 승인 쪽은 응답을 못 준다.
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskApprovalRepository approvals;
    private final Clock clock;

    @Transactional
    public TaskApproval approve(Long pipelineId, Long taskId, String approverId, String approverName,
            ApprovalChannel channel) {
        return decide(pipelineId, taskId, ApprovalStatus.APPROVED, approverId, approverName, channel);
    }

    @Transactional
    public TaskApproval reject(Long pipelineId, Long taskId, String approverId, String approverName,
            ApprovalChannel channel) {
        return decide(pipelineId, taskId, ApprovalStatus.REJECTED, approverId, approverName, channel);
    }

    private TaskApproval decide(Long pipelineId, Long taskId, ApprovalStatus decision, String approverId,
            String approverName, ApprovalChannel channel) {
        if (approverId == null || approverId.isBlank()) {
            throw new ApproverRequiredException();
        }
        // 잠금 순서 고정: 승인 행보다 파이프라인을 먼저 잡는다
        Pipeline pipeline = pipelines.findByIdForUpdate(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
        requireApprovalGate(pipelineId, taskId);
        Instant now = clock.instant();
        if (approvals.decide(taskId, decision, TaskApproval.clampApprover(approverId),
                TaskApproval.clampApprover(approverName), channel, now) == 0) {
            return alreadySettled(taskId);
        }
        wakeUp(pipeline, decision, now);
        return approvals.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotAwaitingApprovalException(taskId));
    }

    /** 결정할 수 있는 대상인지 확인한다 — 그 파이프라인의 태스크이고, 승인 게이트여야 한다. */
    private void requireApprovalGate(Long pipelineId, Long taskId) {
        Task task = tasks.findById(taskId)
                .filter(candidate -> candidate.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new TaskNotFoundException(pipelineId, taskId));
        if (task.getOperation() == null || !task.getOperation().isApprovalGate()) {
            throw new TaskNotAwaitingApprovalException(taskId);
        }
    }

    /**
     * 조건부 UPDATE가 한 행도 못 바꿨을 때, 행을 다시 읽어 두 가지를 구분한다. 이미 결말이 난 요청과, 아직
     * 대기 중으로 보이지만 기한이 지나 버린 요청은 사용자에게 전혀 다른 이야기다 — 후자는 "다른 사람이
     * 처리했다"가 아니라 "시간이 지났다"이므로, 뭉뚱그리면 사실과 다른 안내가 된다.
     *
     * 사람이 내린 결정(승인·반려)만 오류가 아니라 그 결정을 그대로 돌려준다. 같은 결정 요청이 두 번
     * 도착하는 것은 예외 상황이 아니라 정상이기 때문이다 — 승인자가 두 번 누르거나, 응답이 유실돼
     * 재전송되거나, Slack 배달이 같은 클릭을 두 번 전하는 일이 모두 여기로 온다. 이때 오류를 돌려주면 이미
     * 커밋된 결정을 두고 "실패했다"고 말하는 셈이라, 호출자는 성공한 승인을 실패로 처리하게 된다. 돌려준
     * 행에 누가 언제 무엇을 결정했는지가 담겨 있으므로, 화면은 "이미 ○○ 님이 처리했습니다"로 안내한다.
     *
     * 시스템이 닫은 요청(만료·취소)은 그렇지 않다. 겉보기에는 똑같이 "이미 결정됨"이지만 행위자가
     * 없어서, 그대로 돌려주면 승인자가 없는 승인 응답이 되고 화면은 "이미 (아무개 없음) 님이
     * 처리했습니다"를 띄운다. 실행은 이미 실패했거나 취소됐는데 승인이 된 것처럼 보이는 셈이다.
     *
     * 더 나쁜 것은 그 답이 워커가 언제 쓸고 갔느냐에 따라 달라진다는 점이다. 기한이 지난 뒤의 승인
     * 클릭은 워커가 만료를 기록하기 전이면 아직 REQUESTED라 거절되고, 기록한 뒤면 EXPIRED가 되는데, 이
     * 둘을 다르게 답하면 같은 사용자 행동이 초 단위 타이밍으로 성공과 실패를 오간다. 그래서 만료는 아직
     * 기록되지 않았든 이미 기록됐든 같은 답을 준다.
     *
     * "결정됐는가"를 한 술어로 묶지 않고 상태별로 나누는 이유가 이것이다. 묶어 두면 나중에 값이 하나 늘
     * 때 그것이 사람의 결정인지 시스템의 정리인지 아무도 묻지 않게 된다 — switch로 두면 컴파일러가 여기를
     * 지목해 그 질문을 강제한다.
     */
    private TaskApproval alreadySettled(Long taskId) {
        TaskApproval current = approvals.findByTaskId(taskId)
                .orElseThrow(() -> new TaskNotAwaitingApprovalException(taskId));
        return switch (current.getStatus()) {
            case APPROVED, REJECTED -> current;
            // 아직 REQUESTED인데 CAS가 졌다는 것은 시간 조건에 걸렸다는 뜻이다(만료 기록 직전 구간).
            case REQUESTED, EXPIRED -> throw new ApprovalDeadlinePassedException(taskId);
            case CANCELLED -> throw new TaskNotAwaitingApprovalException(taskId);
        };
    }

    /**
     * 결정이 남았으니 파이프라인을 지금 처리 대상으로 되돌린다 — 그러지 않으면 만료 시각까지 잠든 채로
     * 남아 승인이 반영되지 않는다. 반려는 취소 요청 표시도 함께 세운다.
     */
    private void wakeUp(Pipeline pipeline, ApprovalStatus decision, Instant now) {
        pipeline.setNextDueAt(now);
        if (decision == ApprovalStatus.REJECTED) {
            pipeline.setCancelRequested(true);
        }
    }
}
