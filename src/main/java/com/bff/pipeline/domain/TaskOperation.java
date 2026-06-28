package com.bff.pipeline.domain;

/**
 * The domain action a task performs — the ADR's conditional sixth enum, kept as a first-class
 * type because the v1 operation set is <em>closed</em> (ADR-016 §2). A task's {@link TaskKind}
 * selects the executor; its operation selects the action within it.
 *
 * <p>When the operation set later becomes open/configured, this enum gives way to a registry
 * (see {@code docs/extensibility.md}); until then a closed enum keeps it type-safe.
 */
public enum TaskOperation {
    APPLY_NETWORK,
    NETWORK_READY,
    DESTROY_NETWORK
}
