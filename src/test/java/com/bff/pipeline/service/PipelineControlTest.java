package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-cancel behavior ({@link PipelineControl}): a cancel terminalizes every non-terminal task and
 * frees the target, is idempotent on an already-terminal run, lets a fresh run start for the target once
 * a prior run is terminal, and — via the RUNNING-guarded CAS — does not resurrect a pipeline that
 * already converged to a terminal state. {@code NOT_SUPPORTED} suppresses the test-wrapping transaction
 * so create commits and cancel reads committed state.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineControl.class, PipelineCreator.class, PipelineInserter.class, Recipes.class,
        PipelineControlTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineControlTest {

    @Autowired private PipelineControl control;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskRepository tasks;

    @AfterEach
    void clean() {
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void cancelTerminalizesEveryNonTerminalTaskAndFreesTheTarget() {
        Pipeline pipeline = creator.create("c-1", PipelineType.INSTALL);

        Pipeline cancelled = control.cancel(pipeline.getId());

        assertThat(cancelled.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(cancelled.getActiveTarget()).isNull();
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()))
                .extracting(Task::getStatus)
                .containsOnly(TaskStatus.CANCELLED);
    }

    @Test
    void cancelIsIdempotentOnAnAlreadyTerminalPipeline() {
        Pipeline pipeline = creator.create("c-2", PipelineType.DELETE);
        control.cancel(pipeline.getId());

        Pipeline again = control.cancel(pipeline.getId());

        assertThat(again.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
    }

    @Test
    void aNewRunIsAllowedForTheTargetAfterCancel() {
        Pipeline first = creator.create("c-3", PipelineType.INSTALL);
        control.cancel(first.getId());

        Pipeline second = creator.create("c-3", PipelineType.INSTALL);

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void cancelDoesNotResurrectAPipelineThatAlreadyConvergedToTerminal() {
        Pipeline pipeline = creator.create("c-4", PipelineType.DELETE);
        Pipeline converged = pipelines.findById(pipeline.getId()).orElseThrow();
        converged.setStatus(PipelineStatus.DONE);
        converged.setActiveTarget(null);
        pipelines.save(converged);

        Pipeline result = control.cancel(pipeline.getId());

        assertThat(result.getStatus()).isEqualTo(PipelineStatus.DONE);
        assertThat(tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()))
                .noneMatch(task -> task.getStatus() == TaskStatus.CANCELLED);
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
