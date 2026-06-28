package com.bff.pipeline.create;

import com.bff.pipeline.create.Recipe.Step;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.domain.TaskOperation;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Code-default recipe per pipeline type. A new shape is a new entry here, not a schema change. */
@Component
public class Recipes {

    private final Map<PipelineType, Recipe> byType = Map.of(
            PipelineType.INSTALL, new Recipe(List.of(
                    Step.terraform(TaskOperation.APPLY_NETWORK),
                    Step.condition(TaskOperation.NETWORK_READY))),
            PipelineType.DELETE, new Recipe(List.of(
                    Step.terraform(TaskOperation.DESTROY_NETWORK))));

    public Recipe forType(PipelineType type) {
        Recipe recipe = byType.get(type);
        if (recipe == null) {
            throw new IllegalArgumentException("no recipe for pipeline type " + type);
        }
        return recipe;
    }
}
