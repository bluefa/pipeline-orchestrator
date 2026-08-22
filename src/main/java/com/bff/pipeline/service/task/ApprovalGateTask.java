package com.bff.pipeline.service.task;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.DispatchResult;
import com.bff.pipeline.model.TaskProgress;
import com.bff.pipeline.model.TaskType;
import org.springframework.stereotype.Component;

/**
 * 사람의 승인을 기다리는 {@link TaskType} 구현체다(승인 게이트 ADR §결정 1·2). 다른 task type과 달리
 * InfraManager를 부르지 않는다 — 이 단계가 기다리는 것은 외부 시스템이 아니라 사람이기 때문이다.
 *
 * 실행 내용은 아직 비어 있다. 이 PR은 승인 단계를 카탈로그와 레시피에 등록하는 데까지이고, 대기에
 * 들어가는 동작은 승인 요청 테이블과 함께 다음 PR에서 채운다. 그때까지 이 타입이 등록만 돼 있어야 하는
 * 이유는, 태스크 타입 레지스트리가 부팅에서 "모든 operation에 대응하는 구현이 있는가"를 검사하기
 * 때문이다 — 그 검사는 설정을 꺼도 우회되지 않는다.
 *
 * 그래서 도달하면 조용히 넘기지 않고 멈춘다. 기본값이 꺼짐이라 정상 경로에서는 여기 오지 않지만,
 * 켠 채로 이 PR만 배포하면 게이트 태스크에서 멈춘다 — 다음 PR과 함께 켜는 것이 전제다.
 */
@Component
public class ApprovalGateTask implements TaskType {

    public static final String NAME = TaskOperation.Mechanism.APPROVAL;

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public DispatchResult execute(String target, Task task) {
        throw notWiredYet(task);
    }

    @Override
    public TaskProgress check(String target, Task task, TaskAttempt attempt) {
        throw notWiredYet(task);
    }

    @Override
    public TaskProgress checkWithoutAttempt(String target, Task task) {
        throw notWiredYet(task);
    }

    private static IllegalStateException notWiredYet(Task task) {
        return new IllegalStateException("approval gate task " + task.getId()
                + " cannot run yet: the approval request table lands in the next change");
    }
}
