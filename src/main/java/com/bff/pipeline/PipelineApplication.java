package com.bff.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Install/Delete pipeline orchestrator (ADR-016 domain model).
 *
 * <p>A durable, 6-state task machine driven by a periodic reconciler tick. The database row
 * <em>is</em> the state; a restart resumes from it. See {@code docs/adr/016-...} for the
 * domain model and {@code docs/exception-strategy.md} for how failures are handled.
 */
@SpringBootApplication
@EnableScheduling
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
