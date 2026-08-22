package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.config.ApprovalSettings;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.enums.TaskDefinition;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * (cloud provider, pipeline type)별 recipe 카탈로그다(설계 docs/task-catalog-extension-plan.md §2). 예전의
 * PipelineType switch를 대체해, RecipeDefinition 항목을 (provider, type) 키의 Map으로 모은다. 새 파이프라인 형태는
 * RecipeDefinition에 항목을 추가하면 된다 — 이 클래스는 손대지 않는다.
 *
 * 부팅 시 fail-fast로 검증한다: 각 recipe의 provider가 그 recipe가 참조하는 모든 step TaskDefinition의 provider와
 * 일치하는지(§3). (mechanism이 등록된 TaskType을 갖는지는 TaskTypeRegistry가 검증한다.)
 *
 * 승인 게이트 변형의 선택. 같은 (provider, type)에 승인 단계가 있는 레시피와 없는 레시피가 나란히
 * 선언돼 있다(승인 게이트 ADR §결정 1). 활성으로 등록되는 것은 여전히 조합당 하나이며,
 * {@code pipeline.approval.enabled}가 어느 쪽인지 정한다 — 꺼져 있으면 게이트 변형을 아예 등록하지 않고,
 * 켜져 있으면 게이트 변형이 있는 조합에서 그쪽을 쓴다. 게이트 변형이 없는 조합(Azure·IDC·삭제 레시피)은
 * 설정과 무관하게 원래 레시피 그대로다. 선택은 부팅 시 한 번 고정되므로 조합당 하나라는 검사도 그대로
 * 성립한다.
 *
 * 등록에서 빠진 변형도 이름 해석은 계속 된다({@code RecipeDefinition.find}) — 진행 중이거나 지나간
 * 파이프라인 행에 저장된 이름이 표시·검증에서 깨지지 않아야 하기 때문이다. 설정을 껐다 켜도 이미 만들어진
 * 파이프라인은 자기 레시피대로 끝까지 간다.
 */
@Component
public class RecipeCatalog {

    private record Key(CloudProvider provider, PipelineType type) { }

    private final Map<Key, RecipeDefinition> byKey;

    public RecipeCatalog(ApprovalSettings approvalSettings) {
        Map<Key, RecipeDefinition> gated = new HashMap<>();
        Map<Key, RecipeDefinition> plain = new HashMap<>();
        for (RecipeDefinition recipe : RecipeDefinition.values()) {
            for (TaskDefinition step : recipe.steps()) {
                if (step.provider() != recipe.provider()) {
                    throw new IllegalStateException("RecipeDefinition " + recipe.name() + " (provider " + recipe.provider()
                            + ") references step " + step.name() + " of provider " + step.provider());
                }
            }
            register(recipe.hasApprovalGate() ? gated : plain, recipe);
        }
        this.byKey = select(gated, plain, approvalSettings.enabled());
    }

    /**
     * 같은 종류(게이트 있음/없음)끼리만 모아 중복을 잡는다. 종류를 섞어 세면 검사가 설정에 따라 달라진다 —
     * 게이트 변형과 겹친 중복은 설정이 켜져 있을 때 "변형 선택"으로 보여 그냥 통과해 버리고, 꺼져 있을 때만
     * 부팅이 실패한다. 잘못된 선언은 설정과 무관하게 언제나 부팅에서 걸려야 한다.
     */
    private static void register(Map<Key, RecipeDefinition> sameKind, RecipeDefinition recipe) {
        Key key = new Key(recipe.provider(), recipe.pipelineType());
        RecipeDefinition existing = sameKind.putIfAbsent(key, recipe);
        if (existing != null) {
            throw new IllegalStateException("Two recipes for (" + recipe.provider() + ", " + recipe.pipelineType()
                    + "): " + existing.name() + " and " + recipe.name());
        }
    }

    /**
     * 조합마다 활성 레시피 하나를 고른다. 설정이 켜져 있으면 게이트 변형이 있는 조합에서 그쪽을 쓰고,
     * 꺼져 있으면 게이트 변형은 아예 등록하지 않는다. 게이트 변형만 있고 짝이 없는 조합은 설정이 꺼지면
     * 그 조합 자체가 지원되지 않는 것으로 남는다(현재 그런 조합은 없다).
     */
    private static Map<Key, RecipeDefinition> select(Map<Key, RecipeDefinition> gated,
            Map<Key, RecipeDefinition> plain, boolean approvalEnabled) {
        Map<Key, RecipeDefinition> active = new HashMap<>(plain);
        if (approvalEnabled) {
            active.putAll(gated);
        }
        return Map.copyOf(active);
    }

    /** (provider, type)에 해당하는 recipe. 지원하지 않는 조합이면 empty(호출자가 400으로 거절). */
    public Optional<RecipeDefinition> forProviderAndType(CloudProvider provider, PipelineType type) {
        return Optional.ofNullable(byKey.get(new Key(provider, type)));
    }
}
