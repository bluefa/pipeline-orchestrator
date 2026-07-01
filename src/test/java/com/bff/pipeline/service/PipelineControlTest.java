package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.exception.MissingPipelineIdException;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.Recipes;
import com.bff.pipeline.service.task.ObservationRecorder;
import com.bff.pipeline.service.task.TaskCanceller;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PipelineControl}의 어드민 취소(cancel) 동작을 검증한다. cancel은 비종료 상태인 모든 태스크를
 * 종료 상태로 전환하고 target을 해제한다. 이미 종료된 실행에 대해서는 멱등성(idempotent)을 보장하며,
 * 이전 실행이 종료된 후에는 동일 target으로 새 실행을 시작할 수 있다. 또한 {@code RUNNING} 상태를
 * 가드로 사용하는 CAS(Compare-And-Set)를 통해 이미 종료 상태로 수렴한 파이프라인이 부활하지 않음을
 * 확인한다. {@code NOT_SUPPORTED}가 테스트 래핑 트랜잭션을 억제하여 생성 커밋과 cancel이 커밋된 상태를
 * 읽도록 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineControl.class, TaskCanceller.class, ObservationRecorder.class, PipelineCreator.class,
        PipelineInserter.class, Recipes.class, PipelineControlTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineControlTest {

    @Autowired private PipelineControl control;
    @Autowired private PipelineCreator creator;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void cancelTerminalizesEveryNonTerminalTaskAndFreesTheTarget() {
        Pipeline pipeline = creator.create("c-1", PipelineType.INSTALL);

        Pipeline cancelled = control.cancel(pipeline.getId());

        assertThat(cancelled.getStatus()).isEqualTo(PipelineStatus.CANCELLED);
        assertThat(cancelled.getActiveTarget()).isNull();
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()))
                .extracting(Task::getStatus)
                .containsOnly(TaskStatus.CANCELLED);
    }

    @Test
    void cancelRejectsANullPipelineIdAsABadRequest() {
        MissingPipelineIdException exception =
                catchThrowableOfType(() -> control.cancel(null), MissingPipelineIdException.class);

        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.code()).isEqualTo("ORCHESTRATION_PIPELINE_ID_REQUIRED");
    }

    @Test
    void cancelRejectsAMissingPipelineAsNotFound() {
        PipelineNotFoundException exception =
                catchThrowableOfType(() -> control.cancel(999_999L), PipelineNotFoundException.class);

        assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.code()).isEqualTo("ORCHESTRATION_PIPELINE_NOT_FOUND");
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
        Pipeline converged = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        converged.setStatus(PipelineStatus.DONE);
        converged.setActiveTarget(null);
        pipelineRepository.save(converged);

        Pipeline result = control.cancel(pipeline.getId());

        assertThat(result.getStatus()).isEqualTo(PipelineStatus.DONE);
        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()))
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
