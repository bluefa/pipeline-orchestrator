package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.Recipes;
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
 * {@code active_target} unique constraint를 통한 target별 유일성(uniqueness)을 검증한다.
 *
 * <p>{@code NOT_SUPPORTED}가 테스트 래핑 트랜잭션을 억제하므로 {@link PipelineInserter}가 독립적으로
 * 커밋한다. 두 번째 insert가 실제 unique 제약 위반을 발생시키는 것은 첫 번째 커밋이 완료된 이후에만
 * 가능하다. {@code replace = NONE}은 {@code application.yml}에 정의된 H2 MySQL-mode 데이터소스를
 * 유지한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineCreator.class, PipelineInserter.class, Recipes.class, PipelineUniquenessTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineUniquenessTest {

    @Autowired private PipelineCreator creator;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
    }

    @Test
    void duplicateCreateForAnActiveTargetIsRejectedAsConflict() {
        creator.create("target-a", PipelineType.INSTALL);

        assertThatThrownBy(() -> creator.create("target-a", PipelineType.INSTALL))
                .isInstanceOf(PipelineAlreadyActiveException.class);
        assertThat(pipelineRepository.findAll()).hasSize(1);
    }

    @Test
    void aDifferentTypeCreateForAnActiveTargetIsRejectedAsConflict() {
        creator.create("target-b", PipelineType.INSTALL);

        assertThatThrownBy(() -> creator.create("target-b", PipelineType.DELETE))
                .isInstanceOf(PipelineAlreadyActiveException.class);
        assertThat(pipelineRepository.findAll()).hasSize(1);
    }

    @Test
    void aNewRunIsAllowedForATargetOnceItsPriorRunIsTerminal() {
        Pipeline first = creator.create("target-c", PipelineType.DELETE);
        terminate(first);

        Pipeline second = creator.create("target-c", PipelineType.DELETE);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(pipelineRepository.findById(first.getId()).orElseThrow().getActiveTarget()).isNull();
    }

    private void terminate(Pipeline pipeline) {
        pipeline.setStatus(PipelineStatus.DONE);
        pipeline.setActiveTarget(null);
        pipelineRepository.save(pipeline);
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
