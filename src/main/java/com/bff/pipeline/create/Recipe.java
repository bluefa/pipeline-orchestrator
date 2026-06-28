package com.bff.pipeline.create;

import com.bff.pipeline.domain.TaskKind;
import com.bff.pipeline.domain.TaskOperation;
import java.util.List;

/** The ordered task chain for a pipeline type (ADR-016 §2: "a code default per (type, provider)"). */
public record Recipe(List<Step> steps) {

    public Recipe {
        steps = List.copyOf(steps);
    }

    public record Step(TaskKind kind, TaskOperation operation) {
        public static Step terraform(TaskOperation operation) {
            return new Step(TaskKind.TERRAFORM_JOB, operation);
        }

        public static Step condition(TaskOperation operation) {
            return new Step(TaskKind.CONDITION_CHECK, operation);
        }
    }
}
