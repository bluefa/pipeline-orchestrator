package com.bff.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Install/Delete pipeline durable state-machine (ADR-016 / PR #511 domain model).
 *
 * <p>The database row <em>is</em> the state. The domain exposes one forward-progress operation,
 * {@code PipelineEngine.advance(pipelineId)}, which moves a pipeline's state machine one step. An
 * ADR-021 runner decides when, how often, and with what concurrency to call it — that execution model
 * is out of scope here (there is no scheduler or reconciler loop in this module). See
 * {@code docs/adr/016-...} for the domain model and {@code docs/exception-strategy.md} for failures.
 */
@SpringBootApplication
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
