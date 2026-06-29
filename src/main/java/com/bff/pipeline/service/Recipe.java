package com.bff.pipeline.service;

import com.bff.pipeline.enums.TaskOperation;
import java.util.List;

/** The ordered task chain for a pipeline type (ADR-016 §2: "a code default per (type, provider)"). */
public record Recipe(List<Step> steps) {

    public Recipe {
        steps = List.copyOf(steps);
    }

    /** One step: which task type (by name) runs which operation. The factories name the type once. */
    public record Step(String taskName, TaskOperation operation) {
        public static Step terraform(TaskOperation operation) {
            return new Step(TerraformTask.NAME, operation);
        }

        public static Step condition(TaskOperation operation) {
            return new Step(ConditionCheckTask.NAME, operation);
        }
    }
}
