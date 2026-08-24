package com.bff.pipeline.service.approval;

import com.bff.pipeline.entity.Task;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 승인 게이트가 근거로 읽는 Plan 단계가 어느 것인지 정한다(승인 게이트 ADR §결정 5).
 *
 * 짝짓기를 한곳에 모으는 이유는 같은 질문을 양쪽에서 하기 때문이다. 요약을 만드는 쪽은 "이 게이트는 어느
 * Plan의 로그를 읽나"를 묻고({@link PlanSummaryExtractor}), Plan을 끝내는 쪽은 "내 로그를 게이트가 읽나"를
 * 묻는다({@link PlanLogEvidence}). 두 답이 어긋나면 아무도 읽지 않을 로그 때문에 Plan이 실패하거나, 반대로
 * 근거가 사라진 채로 승인 요청이 나간다.
 */
public final class ApprovalPlanSource {

    private ApprovalPlanSource() {
    }

    /** 게이트가 근거로 읽을 Plan — 게이트보다 순번이 낮은 terraform Plan 중 가장 뒤엣것이다. */
    public static Optional<Task> forGate(Task gate, List<Task> chain) {
        Task found = null;
        for (Task candidate : chain) {
            if (candidate.getSequence() >= gate.getSequence()) {
                break;   // 체인은 순번 오름차순이다
            }
            if (candidate.isTerraformPlan()) {
                found = candidate;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * 이 Plan의 로그를 승인 게이트가 읽는가. 체인의 어느 게이트든 이 Plan을 근거로 고르면 참이다 —
     * "게이트보다 앞"이 아니라 "게이트가 고른 그것"이 기준이라, 같은 체인의 다른 Plan은 해당되지 않는다.
     */
    public static boolean feedsGate(Task plan, List<Task> chain) {
        return chain.stream()
                .filter(Task::isApprovalGate)
                .anyMatch(gate -> forGate(gate, chain)
                        .filter(source -> Objects.equals(source.getId(), plan.getId()))
                        .isPresent());
    }
}
