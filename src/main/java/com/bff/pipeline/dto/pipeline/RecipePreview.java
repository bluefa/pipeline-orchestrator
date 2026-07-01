package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 실행 전 recipe 미리보기다(P9). 실제로 만들어질 task 체인을 순서대로 보여준다. provider는 target으로부터
 * 조회해 고른 recipe의 provider이며, recipeDefinition은 그 RecipeDefinition 상수 이름이다.
 * 와이어 필드는 snake_case로 직렬화한다.
 */
public record RecipePreview(
        @JsonProperty("type") PipelineType type,
        @JsonProperty("provider") CloudProvider provider,
        @JsonProperty("recipe_definition") String recipeDefinition,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("steps") List<RecipePreviewStep> steps) {

    public static RecipePreview from(RecipeDefinition recipe) {
        List<RecipePreviewStep> steps = IntStream.range(0, recipe.steps().size())
                .mapToObj(index -> RecipePreviewStep.from(index, recipe.steps().get(index)))
                .toList();
        return new RecipePreview(recipe.pipelineType(), recipe.provider(), recipe.name(),
                recipe.displayName(), recipe.description(), steps);
    }
}
