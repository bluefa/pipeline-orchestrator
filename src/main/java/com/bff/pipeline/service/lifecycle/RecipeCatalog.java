package com.bff.pipeline.service.lifecycle;

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
 */
@Component
public class RecipeCatalog {

    private record Key(CloudProvider provider, PipelineType type) { }

    private final Map<Key, RecipeDefinition> byKey;

    public RecipeCatalog() {
        Map<Key, RecipeDefinition> map = new HashMap<>();
        for (RecipeDefinition recipe : RecipeDefinition.values()) {
            for (TaskDefinition step : recipe.steps()) {
                if (step.provider() != recipe.provider()) {
                    throw new IllegalStateException("RecipeDefinition " + recipe.name() + " (provider " + recipe.provider()
                            + ") references step " + step.name() + " of provider " + step.provider());
                }
            }
            RecipeDefinition clash = map.putIfAbsent(new Key(recipe.provider(), recipe.pipelineType()), recipe);
            if (clash != null) {
                throw new IllegalStateException("Two recipes for (" + recipe.provider() + ", " + recipe.pipelineType()
                        + "): " + clash.name() + " and " + recipe.name());
            }
        }
        this.byKey = Map.copyOf(map);
    }

    /** (provider, type)에 해당하는 recipe. 지원하지 않는 조합이면 empty(호출자가 400으로 거절). */
    public Optional<RecipeDefinition> forProviderAndType(CloudProvider provider, PipelineType type) {
        return Optional.ofNullable(byKey.get(new Key(provider, type)));
    }
}
