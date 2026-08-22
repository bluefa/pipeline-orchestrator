package com.bff.pipeline.service.task;

import com.bff.pipeline.config.ApprovalSettings;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import com.bff.pipeline.service.approval.PlanSummaryExtractor;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사람의 승인을 기다리는 {@link TaskType} 구현체다(승인 게이트 ADR §결정 1·2). 다른 task type과 달리
 * InfraManager를 부르지 않는다 — 이 단계가 기다리는 것은 외부 시스템이 아니라 사람이기 때문이다.
 *
 * {@code execute}가 하는 일은 두 가지다. 승인 만료 시각을 정하고(지금 + 설정된 대기 상한), 승인자가 볼
 * plan 요약을 앞 단계의 로그에서 뽑는다. 그 둘을 담아 "승인 대기로 들어간다"는 결과를 돌려주면, 엔진이
 * write-back 트랜잭션에서 승인 요청 행을 만들고 태스크를 AWAIT_APPROVAL로 옮긴다. 여기서 행을 직접 쓰지
 * 않는 이유는 이 메서드가 트랜잭션 밖에서 돌기 때문이다 — 요청 행 생성과 상태 전이는 반드시 한 트랜잭션
 * 안에서 함께 커밋돼야 한다.
 *
 * {@code check} 계열은 호출되지 않는다. 대기 중인 게이트를 깨웠을 때 무엇을 할지는 승인 행을 잠근 채
 * 판정해야 하므로, 엔진이 이 타입에 한해 폴링 대신 write-back 트랜잭션으로 판정을 넘긴다
 * ({@code StepRunner}의 AWAIT_APPROVAL 분기). 그래서 두 메서드는 구현 대신 계약 위반을 알린다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalGateTask implements TaskType {

    public static final String NAME = TaskOperation.Mechanism.APPROVAL;

    private final PlanSummaryExtractor planSummaryExtractor;
    private final ApprovalSettings approvalSettings;
    private final Clock clock;

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public DispatchResult execute(String target, Task task) {
        return DispatchResult.awaitApproval(
                clock.instant().plus(approvalSettings.timeout()),
                planSummaryExtractor.summarize(task));
    }

    @Override
    public TaskProgress check(String target, Task task, TaskAttempt attempt) {
        throw unreachable(task);
    }

    @Override
    public TaskProgress checkWithoutAttempt(String target, Task task) {
        throw unreachable(task);
    }

    /** 승인 게이트의 판정은 폴링이 아니라 write-back 트랜잭션의 몫이다 — 여기 도달했다면 분기가 빠진 것이다. */
    private static IllegalStateException unreachable(Task task) {
        return new IllegalStateException("approval gate task " + task.getId()
                + " must be resolved in the write-back transaction, not by polling");
    }
}
