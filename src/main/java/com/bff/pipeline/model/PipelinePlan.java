package com.bff.pipeline.model;

import com.bff.pipeline.enums.RecipeDefinition;
import java.util.Objects;

/**
 * create 요청이 해석된 결과 — 무엇에(target) 어떤 recipe를 실행할지다. PipelineCreator가 provider를 조회해 recipe를
 * 고른 뒤 이 값으로 PipelineInserter에 넘긴다. provider와 pipeline type은 recipe가 이미 들고 있으므로 따로 담지 않는다.
 */
public record PipelinePlan(String target, RecipeDefinition recipe) {

    public PipelinePlan {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        Objects.requireNonNull(recipe, "recipe must not be null");
    }
}
