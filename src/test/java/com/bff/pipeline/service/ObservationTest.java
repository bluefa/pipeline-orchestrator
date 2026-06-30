package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관찰(observation) 전용 테이블의 쓰기 동작을 검증한다. 시도(attempt)마다 올바른 {@code attempt_no}를
 * 가진 {@code task_attempt} 행이 하나씩 기록되며, {@code task_check}는 폴링마다 새 행을 추가하는 것이
 * 아니라 attempt당 하나의 행을 제자리에서 갱신함을 확인한다. {@link PipelineEngineTest}의 Wiring
 * (fake InfraManager, 가변 Clock)을 재사용한다. {@code NOT_SUPPORTED}가 테스트 래핑 트랜잭션을
 * 억제하므로 관찰 행들이 각 단계의 커밋 후에도 유지된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineEngine.class, TaskMachine.class, TaskTypeRegistry.class, TerraformTask.class,
        ConditionCheckTask.class, Observations.class, TaskCanceller.class, PipelineCreator.class,
        PipelineInserter.class, Recipes.class, PipelineEngineTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ObservationTest {

    private static final Instant START = Instant.parse("2026-06-23T00:00:00Z");

    @Autowired private PipelineEngine engine;
    @Autowired private PipelineCreator creator;
    @Autowired private TaskRepository tasks;
    @Autowired private PipelineRepository pipelines;
    @Autowired private TaskAttemptRepository attempts;
    @Autowired private TaskCheckRepository checks;
    @Autowired private PipelineEngineTest.MutableClock clock;
    @Autowired private FakeInfraManagerClient infraManager;

    @BeforeEach
    void reset() {
        clock.set(START);
        infraManager.onDispatch(() -> "[\"job-1\"]");
        infraManager.onPoll(TerraformPoll::running);
        infraManager.onCheck(() -> false);
    }

    @AfterEach
    void clean() {
        checks.deleteAll();
        attempts.deleteAll();
        tasks.deleteAll();
        pipelines.deleteAll();
    }

    @Test
    void aHappyTerraformTaskRecordsOneDoneAttemptWithItsJobId() {
        Pipeline pipeline = creator.create("obs-happy", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::success);
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());

        var recorded = attempts.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));

        assertThat(recorded).singleElement().satisfies(attempt -> {
            assertThat(attempt.getAttemptNumber()).isEqualTo(1);
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(attempt.getResponse()).isEqualTo("[\"job-1\"]");
        });
        assertThat(checks.findByTaskAttemptId(recorded.getFirst().getId())).isEmpty();
    }

    @Test
    void aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo() {
        Pipeline pipeline = creator.create("obs-retry", PipelineType.DELETE);
        infraManager.onPoll(TerraformPoll::failure);

        runUntilTerminal(pipeline);

        var recorded = attempts.findByTaskIdOrderByAttemptNumberAsc(taskId(pipeline, 0));
        assertThat(recorded).extracting(TaskAttempt::getAttemptNumber).containsExactly(1, 2);
        assertThat(recorded).allSatisfy(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(attempt.getErrorCode()).isEqualTo(ErrorCode.JOB_FAILED);
        });
    }

    @Test
    void aConditionPolledNotMetUpdatesOneCheckRowInPlace() {
        Pipeline pipeline = createInstallAtConditionInProgress();
        Long conditionTaskId = taskId(pipeline, 1);

        for (int i = 0; i < 3; i++) {
            engine.advance(pipeline.getId());
            clock.advance(Duration.ofMinutes(11));
        }

        TaskAttempt attempt = attempts.findByTaskIdAndAttemptNumber(conditionTaskId, 1).orElseThrow();
        assertThat(checks.findByTaskAttemptId(attempt.getId())).hasValueSatisfying(check -> {
            assertThat(check.getCallCount()).isEqualTo(3);
            assertThat(check.getNotMetCount()).isEqualTo(3);
        });
        assertThat(checks.findAll()).hasSize(1);
    }

    private Pipeline createInstallAtConditionInProgress() {
        Pipeline pipeline = creator.create("obs-cond", PipelineType.INSTALL);
        infraManager.onPoll(TerraformPoll::success);
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());
        engine.advance(pipeline.getId());
        return pipeline;
    }

    private void runUntilTerminal(Pipeline pipeline) {
        for (int i = 0; i < 20; i++) {
            engine.advance(pipeline.getId());
            clock.advance(Duration.ofHours(1));
            if (pipelines.findById(pipeline.getId()).orElseThrow().getStatus() != PipelineStatus.RUNNING) {
                return;
            }
        }
    }

    private Long taskId(Pipeline pipeline, int sequence) {
        return tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).get(sequence).getId();
    }
}
