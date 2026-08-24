package com.bff.pipeline.service.approval;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 승인 게이트 앞 Plan이 승인자에게 내놓을 근거를 실제로 남겼는지 본다(승인 게이트 ADR §결정 5).
 *
 * terraform 로그 수집은 원래 판정에 관여하지 않는 관찰이다(ADR-016). 게이트 앞 Plan에서만 그 규칙에 예외를
 * 둔다 — 로그가 없으면 요약도 없고 콘솔에서 볼 원문도 없어, 승인자가 무엇에 동의하는지 모르는 채 apply
 * 버튼만 보게 되기 때문이다. 게이트가 막으려던 상황이 게이트 안에서 생기는 셈이라, 이 경우에는 Plan을
 * 성공으로 닫지 않고 다시 돌린다.
 *
 * 예외의 범위는 "다시 돌리면 복구되는 결손"까지다. 본문 조회 실패나 행 유실은 새 Plan이 새 로그를 만들어
 * 해결되지만, 절단(본문이 컬럼 상한을 넘김)과 파싱 불일치(로그 포맷 표류)는 같은 Plan을 다시 돌려도 같은
 * 결과다 — 그것까지 실패로 묶으면 terraform 버전이 한 번 바뀔 때 승인 게이트가 붙은 모든 실행이 동시에
 * 멈춘다. 그 둘은 지금처럼 "검증 불가" 요약으로 승인자에게 넘긴다(승인자는 콘솔에서 원문을 본다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanLogEvidence {

    private final TaskRepository tasks;
    private final TerraformResultRepository terraformResults;

    /**
     * 이 Plan 시도가 승인 게이트에 내놓을 근거를 남기지 못했는가. 참이면 호출자는 성공 대신 재시도 가능한
     * 실패로 닫아 Plan을 다시 돌린다.
     */
    public boolean missingForApprovalGate(Task plan, TaskAttempt attempt, Set<String> finishedJobIds) {
        if (!feedsApprovalGate(plan)) {
            return false;
        }
        if (!anyLogBodyMissing(plan, attempt, finishedJobIds)) {
            return false;
        }
        log.warn("task {} attempt {}: plan feeds an approval gate but some job log bodies are missing; re-planning",
                plan.getId(), attempt.getAttemptNumber());
        return true;
    }

    /**
     * 이 Plan의 로그를 읽을 게이트가 체인에 있는가. terraform Plan이 아니면 체인을 읽지도 않는다 —
     * 이 판정은 종결 turn마다 불리고, 대다수 태스크(apply·destroy·조건 확인)는 여기서 끝난다.
     */
    private boolean feedsApprovalGate(Task plan) {
        if (!plan.isTerraformPlan()) {
            return false;
        }
        return ApprovalPlanSource.feedsGate(plan, tasks.findByPipelineIdOrderBySequenceAsc(plan.getPipelineId()));
    }

    /**
     * 종결된 job 수만큼 본문 있는 행이 남았는가. 행이 아예 없는 경우(저장 실패)와 본문이 비어 있는
     * 경우(조회 실패)를 함께 잡으려고 개수로 센다 — 본문을 읽어 확인하면 job 하나가 최대 4백만 자라
     * 판정 경로에서 지불할 비용이 아니다. 절단된 행은 본문이 있으므로 여기서 결손으로 세지 않는다.
     */
    private boolean anyLogBodyMissing(Task plan, TaskAttempt attempt, Set<String> finishedJobIds) {
        long withBody = terraformResults.countByTaskIdAndAttemptNumberAndResultIsNotNull(
                plan.getId(), attempt.getAttemptNumber());
        return withBody < finishedJobIds.size();
    }
}
