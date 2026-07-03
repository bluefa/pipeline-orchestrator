package com.bff.pipeline.model;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.enums.TaskDefinition;
import java.util.List;
import java.util.Objects;

/**
 * create 요청이 해석된 결과 — 무엇에(target) 어떤 순서의 어떤 task 체인을 실행할지다. PipelineCreator가 target
 * provider를 조회해 카탈로그 recipe를 고르거나(catalog 경로) 요청의 custom task 리스트를 검증해(custom 경로) 만든 뒤
 * 이 값으로 PipelineInserter에 넘긴다. 두 경로 모두 같은 꼴이라 inserter는 분기 없이 한 가지로 삽입한다.
 *
 * {@code type}이 이 실행의 분류다 — INSTALL/DELETE는 카탈로그 recipe, {@link PipelineType#CUSTOM}은 요청이 직접
 * 구성한 custom 실행이다(LIN-18). {@code recipeDefinition}은 catalog 경로에서만 RecipeDefinition 상수 이름
 * (예: {@code AWS_INSTALL_V1})을 담고, custom 경로는 백킹 recipe가 없으므로 null이다 — 분류 신호는 type이 진다.
 *
 * 각 {@link PlannedStep}은 실행할 TaskDefinition과 선택적 운영자 설명(custom 경로에서만 채워지고, catalog 경로는
 * null)이다.
 */
public record PipelinePlan(String target, PipelineType type, CloudProvider provider, String recipeDefinition,
        List<PlannedStep> steps) {

    public PipelinePlan {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        steps = List.copyOf(steps);
    }

    /** 카탈로그 recipe로부터 plan을 만든다 — type/provider/recipe 이름/step은 recipe가 이미 들고 있다. step 설명은 없다(null). */
    public static PipelinePlan fromCatalog(String target, RecipeDefinition recipe) {
        Objects.requireNonNull(recipe, "recipe must not be null");
        List<PlannedStep> steps = recipe.steps().stream()
                .map(definition -> new PlannedStep(definition, null))
                .toList();
        return new PipelinePlan(target, recipe.pipelineType(), recipe.provider(), recipe.name(), steps);
    }

    /** 검증을 통과한 custom step 리스트로 plan을 만든다 — type은 {@link PipelineType#CUSTOM}, recipeDefinition은 없다(null). */
    public static PipelinePlan custom(String target, CloudProvider provider, List<PlannedStep> steps) {
        return new PipelinePlan(target, PipelineType.CUSTOM, provider, null, steps);
    }

    /** 체인의 한 단계 — 실행할 TaskDefinition과, custom 경로에서 운영자가 붙인 선택적 설명. */
    public record PlannedStep(TaskDefinition definition, String description) {
        public PlannedStep {
            Objects.requireNonNull(definition, "definition must not be null");
        }
    }
}
