package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.pipeline.PipelineCreator;
import com.bff.pipeline.service.pipeline.PipelineInserter;
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
 * {@code active_target} unique constraint를 통한 target별 유일성(uniqueness)을 검증한다.
 *
 * <p>{@code NOT_SUPPORTED}가 테스트 래핑 트랜잭션을 억제하므로 {@link PipelineInserter}가 독립적으로
 * 커밋한다. 두 번째 insert가 실제 unique 제약 위반을 발생시키는 것은 첫 번째 커밋이 완료된 이후에만
 * 가능하다. {@code replace = NONE}은 {@code application.yml}에 정의된 H2 MySQL-mode 데이터소스를
 * 유지한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineCreator.class, PipelineInserter.class, PipelineUniquenessTest.Wiring.class})
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
        terminate(first);

        Pipeline second = creator.create("target-c", PipelineType.DELETE);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(pipelines.findById(first.getId()).orElseThrow().getActiveTarget()).isNull();
    }

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
