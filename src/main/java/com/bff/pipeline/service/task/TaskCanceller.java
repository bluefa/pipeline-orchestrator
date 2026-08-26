package com.bff.pipeline.service.task;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.execution.StepReporter;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.TaskApprovalRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 체인의 비종료 태스크를 취소 처리하면서, 열려 있던 시도 관찰(attempt observation)도 각각 CANCELLED로 닫아 이력이
 * 거짓말을 하지 않도록 한다. 관리자 취소({@link PipelineControl})와 수렴 시의 실패 연쇄({@link StepReporter})가 이 컴포넌트를
 * 함께 쓴다. 취소 로직을 한곳에 모아 두면 두 호출 지점이 DRY를 지키고, 태스크가 종료되기 전에 관찰이 반드시 먼저 닫힘을 보장한다.
 *
 * 승인을 기다리던 태스크라면 열려 있던 승인 요청도 여기서 함께 닫는다. 두 호출 지점 모두 파이프라인 행을
 * 먼저 확보한 뒤 이 컴포넌트를 부르므로, 승인 행을 만지는 잠금 순서(파이프라인 먼저)도 자연히 지켜진다.
 */
@Component
@RequiredArgsConstructor
public class TaskCanceller {

    private final TaskRepository taskRepository;
    private final TaskApprovalRepository taskApprovalRepository;
    private final ObservationRecorder observationRecorder;
    private final Clock clock;

    public void cancelNonTerminal(List<Task> chain) {
        Instant now = clock.instant();
        chain.stream().filter(task -> !task.getStatus().isTerminal()).forEach(task -> {
            closeApprovalRequest(task, now);
            observationRecorder.endAttempt(task, TaskStatus.CANCELLED, null, null);
            task.setStatus(TaskStatus.CANCELLED);
            task.setFinishedAt(now);
            taskRepository.save(task);
        });
    }

    /**
     * 승인을 기다리던 태스크가 취소되면 열려 있던 승인 요청도 함께 닫는다 — 안 닫으면 파이프라인은 끝났는데
     * 요청만 영영 "대기 중"으로 남아, Slack 메시지의 버튼이 살아 있고 조회 화면도 사실과 다르게 보인다.
     * 아직 결정되지 않은 요청만 대상이라, 이미 승인·반려된 기록은 그대로 보존된다.
     */
    private void closeApprovalRequest(Task task, Instant now) {
        if (task.getStatus() == TaskStatus.AWAIT_APPROVAL) {
            taskApprovalRepository.cancelIfRequested(task.getId(), now);
        }
    }
}
