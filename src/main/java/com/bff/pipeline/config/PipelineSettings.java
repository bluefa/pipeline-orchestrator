package com.bff.pipeline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global reconciler knobs, bound from {@code pipeline.*} in application.yml. A task may override
 * any per-task deadline; when it does not, the value here applies.
 *
 * <p>{@link #defaults()} is the test seam — unit tests construct the settings directly instead of
 * standing up a Spring context.
 */
@ConfigurationProperties(prefix = "pipeline")
public record PipelineSettings(
        Duration tickInterval,
        Duration perCallTimeout,
        Duration executionTimeout,
        Duration ttl,
        Duration pollingInterval,
        int maxFailCount,
        int workerPoolSize) {

    public static PipelineSettings defaults() {
        return new PipelineSettings(
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                Duration.ofMinutes(10),
                3,
                8);
    }
}
