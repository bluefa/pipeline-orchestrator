package com.bff.pipeline.enums;

/**
 * What a pipeline does to a target's infrastructure: INSTALL stands the target up, DELETE tears it
 * down. The value selects the task-chain recipe ({@code Recipes}) and is stored on each pipeline row.
 */
public enum PipelineType {
    INSTALL,
    DELETE
}
