package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.controller.TargetSourcePipelineController;
import com.bff.pipeline.dto.pipeline.CreatePipelineRequest;
import com.bff.pipeline.dto.pipeline.CustomPipelineRequest;
import com.bff.pipeline.dto.pipeline.CustomTaskRequest;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.TaskSummary;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.exception.EmptyCustomRecipeException;
import com.bff.pipeline.exception.TaskDescriptionTooLongException;
import com.bff.pipeline.exception.TaskProviderMismatchException;
import com.bff.pipeline.exception.UnknownTaskException;
import com.bff.pipeline.exception.UnsupportedRecipeException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import com.bff.pipeline.service.query.PipelineQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * LIN-18: custom recipe 실행 API. task 이름 리스트를 요청 순서대로 비영속 실행하고, 검증 위반은 400 예외로 거절하며,
 * 분류는 type=CUSTOM(recipe_definition은 null)으로, task별 설명은 실행 기록에 저장되는지 검증한다. custom 엔드포인트는
 * 카탈로그 엔드포인트와 분리돼 있어 type을 받지 않고 tasks가 비면 400이다. 카탈로그 경로는 종전대로 INSTALL/DELETE로 남는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class, PipelineQueryService.class,
        TargetSourcePipelineController.class, CustomRecipeCreationTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomRecipeCreationTest {

    @Autowired private PipelineCreator creator;
    @Autowired private TargetSourcePipelineController controller;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private FakeInfraManagerClient infraManager;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        infraManager.onCloudProvider(CloudProvider.AWS);
    }

    @Test
    void customTasksRunInRequestOrderAndAreTypedCustom() {
        List<CustomTaskRequest> tasks = List.of(
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply first"),
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "then plan"));

        Pipeline pipeline = creator.createCustom("cust-order", tasks);

        assertThat(pipeline.getType()).isEqualTo(PipelineType.CUSTOM);   // 분류는 type이 진다
        assertThat(pipeline.getRecipeDefinition()).isNull();              // 백킹 RecipeDefinition이 없다
        assertThat(pipeline.getCloudProvider()).isEqualTo(CloudProvider.AWS);
        List<Task> chain = taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
        assertThat(chain).extracting(Task::getTaskDefinition, Task::getDescription).containsExactly(
                tuple(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply first"),
                tuple(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "then plan"));
    }

    @Test
    void customRecipeRunsThroughTheCustomEndpoint() {
        CustomPipelineRequest request = new CustomPipelineRequest(List.of(
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply first"),
                new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "then plan")));

        PipelineDetail detail = controller.createCustom("cust-endpoint", request);

        assertThat(detail.type()).isEqualTo(PipelineType.CUSTOM);
        assertThat(detail.recipeDefinition()).isNull();
        assertThat(detail.tasks()).extracting(TaskSummary::taskDefinition, TaskSummary::description).containsExactly(
                tuple(TaskDefinition.AWS_SERVICE_APPLY_V1.name(), "apply first"),
                tuple(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), "then plan"));
    }

    @Test
    void catalogEndpointStaysTypedInstallOrDelete() {
        PipelineDetail detail = controller.create("cust-endpoint-catalog",
                new CreatePipelineRequest(PipelineType.DELETE));

        assertThat(detail.type()).isEqualTo(PipelineType.DELETE);
        assertThat(detail.recipeDefinition()).isEqualTo("AWS_DELETE_V1");
    }

    @Test
    void nullDescriptionIsAllowed() {
        Pipeline pipeline = creator.createCustom("cust-nodesc",
                List.of(new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), null)));

        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst().getDescription())
                .isNull();
    }

    @Test
    void unknownTaskNameIsRejected() {
        assertThatThrownBy(() -> creator.createCustom("cust-unknown",
                List.of(new CustomTaskRequest("NOT_A_REAL_TASK", null))))
                .isInstanceOf(UnknownTaskException.class);
    }

    @Test
    void providerMismatchIsRejected() {
        infraManager.onCloudProvider(CloudProvider.AWS);   // target is AWS, task is GCP

        assertThatThrownBy(() -> creator.createCustom("cust-mismatch",
                List.of(new CustomTaskRequest(TaskDefinition.GCP_SERVICE_PLAN_V1.name(), null))))
                .isInstanceOf(TaskProviderMismatchException.class);
    }

    @Test
    void descriptionOver100CharsIsRejected() {
        String tooLong = "x".repeat(CustomTaskRequest.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> creator.createCustom("cust-long",
                List.of(new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), tooLong))))
                .isInstanceOf(TaskDescriptionTooLongException.class);
    }

    @Test
    void exactly100CharsIsAccepted() {
        String maxLen = "x".repeat(CustomTaskRequest.MAX_DESCRIPTION_LENGTH);

        Pipeline pipeline = creator.createCustom("cust-max",
                List.of(new CustomTaskRequest(TaskDefinition.AWS_SERVICE_PLAN_V1.name(), maxLen)));

        assertThat(taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId()).getFirst().getDescription())
                .hasSize(CustomTaskRequest.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void emptyTaskListIsRejected() {
        assertThatThrownBy(() -> creator.createCustom("cust-empty", List.of()))
                .isInstanceOf(EmptyCustomRecipeException.class);
        assertThatThrownBy(() -> creator.createCustom("cust-null", null))
                .isInstanceOf(EmptyCustomRecipeException.class);
    }

    @Test
    void catalogEndpointRejectsCustomTypeBeforeProviderLookup() {
        infraManager.onCloudProvider(null);   // provider 조회가 503을 낼 상황

        assertThatThrownBy(() -> creator.create("cat-custom", PipelineType.CUSTOM))
                .isInstanceOf(UnsupportedRecipeException.class);   // 503이 아니라 400 — CUSTOM을 먼저 거절
    }

    @Test
    void malformedCustomBodyStays400EvenWhenProviderLookupFails() {
        infraManager.onCloudProvider(null);   // provider 조회가 503을 낼 상황

        assertThatThrownBy(() -> creator.createCustom("cust-badname",
                List.of(new CustomTaskRequest("NOT_A_REAL_TASK", null))))
                .isInstanceOf(UnknownTaskException.class);   // 이름 검증이 provider 조회보다 먼저라 400 유지
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-06-23T00:00:00Z"));
        }

        @Bean
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).startDelay(Duration.ZERO).build();
        }

        @Bean
        ExecutionSettings executionSettings() {
            return ExecutionSettings.builder()
                    .workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
                    .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
                    .schedulerInitialDelay(Duration.ofSeconds(5))
                    .build();
        }
    }
}
