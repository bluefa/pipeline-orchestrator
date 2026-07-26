package com.bff.pipeline.model;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.enums.TaskDefinition;
import java.util.List;
import java.util.Objects;

/**
 * create 요청이 해석된 결과 — 무엇에(target) 어떤 순서의 어떤 task 체인을 실행할지다. PipelineCreator가 target
 * provider를 조회해 카탈로그 recipe를 고르거나(catalog 경로) 요청의 custom task 리스트를 검증해(custom 경로) 만든 뒤
 * 이 값으로 PipelineInserter에 넘긴다. 재시작(restart 경로)은 실패한 실행의 suffix로 PipelineRestarter가 만든다.
 * 세 경로 모두 같은 꼴이라 inserter는 분기 없이 한 가지로 삽입한다.
 *
 * {@code type}이 이 실행의 분류다 — INSTALL/DELETE는 카탈로그 recipe, {@link PipelineType#CUSTOM}은 요청이 직접
 * 구성한 custom 실행이다(LIN-18). {@code recipeDefinition}은 catalog 경로에서만 RecipeDefinition 상수 이름
 * (예: {@code AWS_INSTALL_V1})을 담고, custom 경로는 백킹 recipe가 없으므로 null이다 — 분류 신호는 type이 진다.
 *
 * {@code originPipelineId}는 재시작 계보다 — restart 경로만 원본 파이프라인 id를 싣고, 나머지 경로는 null이다.
 * 표시용 write-once 메타데이터로 inserter가 행에 스탬핑만 하고 엔진은 읽지 않는다.
 *
 * 각 {@link PlannedStep}은 실행할 TaskDefinition과 선택적 운영자 설명(custom 경로에서만 채워지고, catalog 경로는
 * null), 그리고 재시작 계보(originTaskId — restart 경로에서만)다.
 */
public record PipelinePlan(String target, PipelineType type, CloudProvider provider, String recipeDefinition,
        Long originPipelineId, List<PlannedStep> steps) {

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
        return new PipelinePlan(target, recipe.pipelineType(), recipe.provider(), recipe.name(), null, steps);
    }

    /** 검증을 통과한 custom step 리스트로 plan을 만든다 — type은 {@link PipelineType#CUSTOM}, recipeDefinition은 없다(null). */
    public static PipelinePlan custom(String target, CloudProvider provider, List<PlannedStep> steps) {
        return new PipelinePlan(target, PipelineType.CUSTOM, provider, null, null, steps);
    }

    /**
     * 재시작 plan — type/recipeDefinition을 원본에서 승계하고 계보(originPipelineId)를 실어 만든다(재시작 설계
     * 결정 1·2). provider는 호출자(PipelineRestarter)가 원본 저장값 또는 폴백 조회로 확정해 넘긴다.
     */
    public static PipelinePlan restartOf(Pipeline origin, CloudProvider provider, List<PlannedStep> steps) {
        Objects.requireNonNull(origin, "origin must not be null");
        return new PipelinePlan(origin.getTarget(), origin.getType(), provider,
                origin.getRecipeDefinition(), origin.getId(), steps);
    }

    /** 체인의 한 단계 — 실행할 TaskDefinition, 운영자가 붙인 선택적 설명, 재시작 계보(원본 task 행 id). */
    public record PlannedStep(TaskDefinition definition, String description, Long originTaskId) {
        public PlannedStep {
            Objects.requireNonNull(definition, "definition must not be null");
        }

        /** 기존 catalog/custom 경로용 — 계보 없음. */
        public PlannedStep(TaskDefinition definition, String description) {
            this(definition, description, null);
        }
    }
}
