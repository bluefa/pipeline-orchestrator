package com.bff.pipeline.create;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.domain.Pipeline;
import com.bff.pipeline.domain.PipelineStatus;
import com.bff.pipeline.domain.PipelineType;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-target uniqueness via the {@code active_target} unique constraint.
 *
 * <p>{@code NOT_SUPPORTED} suppresses the test-wrapping transaction so {@link PipelineInserter}
 * commits independently — only then does the second insert surface a real unique violation.
 * {@code replace = NONE} keeps the H2 MySQL-mode datasource from {@code application.yml}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineCreator.class, PipelineInserter.class, Recipes.class, PipelineUniquenessTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineUniquenessTest {

    @Autowired private PipelineCreator creator;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskRepository tasks;

    @AfterEach
    void clean() {
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void duplicateCreateForAnActiveTargetReturnsTheExistingRun() {
        Pipeline first = creator.create("target-a", PipelineType.INSTALL);
        Pipeline again = creator.create("target-a", PipelineType.INSTALL);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(pipelines.findAll()).hasSize(1);
    }

    @Test
    void aDifferentTypeCreateForAnActiveTargetReturnsTheExistingRun() {
        Pipeline install = creator.create("target-b", PipelineType.INSTALL);
        Pipeline delete = creator.create("target-b", PipelineType.DELETE);

        assertThat(delete.getId()).isEqualTo(install.getId());
        assertThat(delete.getType()).isEqualTo(PipelineType.INSTALL);
    }

    @Test
    void aNewRunIsAllowedForATargetOnceItsPriorRunIsTerminal() {
        Pipeline first = creator.create("target-c", PipelineType.DELETE);
        terminate(first); // terminal ⇒ active_target NULL, so the target is reusable

        Pipeline second = creator.create("target-c", PipelineType.DELETE);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(pipelines.findById(first.getId()).orElseThrow().getActiveTarget()).isNull();
    }

    /** Mirror what the reconcile/cancel paths do on terminalization: clear active_target. */
    private void terminate(Pipeline pipeline) {
        pipeline.setStatus(PipelineStatus.DONE);
        pipeline.setActiveTarget(null);
        pipelines.save(pipeline);
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
