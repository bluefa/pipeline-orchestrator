package com.bff.pipeline.service;

import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.service.Recipe.Step;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The code-default recipe (ordered task chain) for each {@link PipelineType} — ADR-016 §2's "a code
 * default per (type, provider)". A new pipeline shape is a new entry here, not a schema change.
 * {@code forType} is exhaustive over {@link PipelineType}, so a new type is a compile error here rather
 * than a runtime miss: INSTALL applies the network then waits for it to be ready, DELETE destroys it.
 */
@Component
public class Recipes {

    private static final Recipe INSTALL_RECIPE = new Recipe(List.of(
            Step.terraform(TaskOperation.APPLY_NETWORK),
            Step.condition(TaskOperation.NETWORK_READY)));

    private static final Recipe DELETE_RECIPE = new Recipe(List.of(
            Step.terraform(TaskOperation.DESTROY_NETWORK)));

    public Recipe forType(PipelineType type) {
        return switch (type) {
            case INSTALL -> INSTALL_RECIPE;
            case DELETE -> DELETE_RECIPE;
        };
    }
}
