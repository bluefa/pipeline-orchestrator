package com.bff.pipeline;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global per-task deadline defaults, bound from {@code pipeline.*} in application.yml. A task may
 * override any of these; when it does not, the value here applies. (Execution cadence — tick interval,
 * per-call timeout, worker-pool size — belongs to the ADR-021 runner and is not configured here.)
 *
 * <p>Validated at construction: a missing {@code pipeline.*} key would otherwise bind to {@code null}
 * and surface later as an NPE in the deadline math ({@code TaskKnobs}); instead an incomplete config
 * fails fast at startup with the offending key named.
 */
@ConfigurationProperties(prefix = "pipeline")
public record PipelineSettings(
        Duration executionTimeout,
        Duration timeToLive,
        Duration pollingInterval,
        int maxFailCount) {

    public PipelineSettings {
        Objects.requireNonNull(executionTimeout, "pipeline.execution-timeout must be set");
        Objects.requireNonNull(timeToLive, "pipeline.time-to-live must be set");
        Objects.requireNonNull(pollingInterval, "pipeline.polling-interval must be set");
        if (maxFailCount < 1) {
            throw new IllegalArgumentException("pipeline.max-fail-count must be >= 1, was " + maxFailCount);
        }
    }
}
