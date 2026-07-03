package com.bff.pipeline.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RecipeDefinition 카탈로그의 완전성과 step 구성 규약을 못박는다. (provider, type) 중복과 step provider 불일치는
 * RecipeCatalog가 부팅에서 잡지만, "전 조합이 존재하는가"와 "install/delete step 구성이 규약대로인가"는 어느 쪽도
 * 강제하지 못하므로 여기서 회귀로 지킨다.
 */
class RecipeDefinitionTest {

    @Test
    void everyProviderHasAnInstallAndADeleteRecipe() {
        for (CloudProvider provider : CloudProvider.values()) {
            for (PipelineType type : PipelineType.values()) {
                assertThat(recipeFor(provider, type)).as("(%s, %s)", provider, type).isNotNull();
            }
        }
    }

    /** install 규약: 실행 단위마다 plan 바로 다음에 같은 단위의 apply가 온다(plan step 포함은 owner 결정, 설계 §5). */
    @Test
    void installRecipesPairEachPlanWithItsApply() {
        for (RecipeDefinition recipe : byType(PipelineType.INSTALL)) {
            List<TaskDefinition> terraformSteps = recipe.steps().stream()
                    .filter(TaskDefinition::consumesTerraformSlot)
                    .toList();
            assertThat(terraformSteps.size() % 2).as("%s pairs", recipe).isZero();
            for (int i = 0; i < terraformSteps.size(); i += 2) {
                TaskDefinition plan = terraformSteps.get(i);
                TaskDefinition apply = terraformSteps.get(i + 1);
                assertThat(plan.operation().name()).as("%s step %d", recipe, i).endsWith("_TF_PLAN");
                assertThat(apply.operation().name()).as("%s step %d", recipe, i + 1)
                        .isEqualTo(plan.operation().name().replace("_TF_PLAN", "_TF_APPLY"));
            }
        }
    }

    /** delete 규약: destroy step만으로 구성된다(plan·조건 확인 없음). */
    @Test
    void deleteRecipesContainOnlyDestroySteps() {
        for (RecipeDefinition recipe : byType(PipelineType.DELETE)) {
            assertThat(recipe.steps()).as("%s", recipe).isNotEmpty()
                    .allSatisfy(step -> assertThat(step.operation().name()).endsWith("_TF_DESTROY"));
        }
    }

    private static RecipeDefinition recipeFor(CloudProvider provider, PipelineType type) {
        return Arrays.stream(RecipeDefinition.values())
                .filter(recipe -> recipe.provider() == provider && recipe.pipelineType() == type)
                .findFirst()
                .orElse(null);
    }

    private static List<RecipeDefinition> byType(PipelineType type) {
        return Arrays.stream(RecipeDefinition.values())
                .filter(recipe -> recipe.pipelineType() == type)
                .toList();
    }
}
