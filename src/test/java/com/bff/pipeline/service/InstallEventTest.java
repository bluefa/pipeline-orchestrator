package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.FakeInfraManagerClient;
import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.entity.InstallEvent;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.InstallEventOutcome;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.repository.InstallEventRepository;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.lifecycle.InstallEventHandler;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.lifecycle.PipelineInserter;
import com.bff.pipeline.service.lifecycle.RecipeCatalog;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 설치 이벤트 처리. Azure/IDC INSTALL 이벤트는 요청자 SYSTEM으로 설치 파이프라인을 열고, 같은 대상에 진행 중인
 * 파이프라인이 있으면 열지 않고 기록만 남기며, AWS/GCP는 보류하고, 잘못된 본문·카탈로그와 다른 provider는 INVALID로
 * 기록한다. 어느 경우든 install_event 행이 정확히 하나 남는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class, InstallEventHandler.class,
        InstallEventTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InstallEventTest {

    private static final String AZURE_INSTALL = """
            {"target_source_id": "ts-azure-1", "cloud_provider": "AZURE", "type": "INSTALL"}""";

    @Autowired private InstallEventHandler handler;
    @Autowired private InstallEventRepository installEvents;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private FakeInfraManagerClient infraManager;

    @AfterEach
    void clean() {
        installEvents.deleteAll();
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        infraManager.onCloudProvider(CloudProvider.AWS);
    }

    @Test
    void anAzureInstallEventOpensTheCatalogInstallAsSystem() {
        infraManager.onCloudProvider(CloudProvider.AZURE);

        InstallEvent event = handler.handle("msg-1", AZURE_INSTALL);

        assertThat(event.getOutcome()).isEqualTo(InstallEventOutcome.STARTED);
        Pipeline pipeline = pipelineRepository.findById(event.getPipelineId()).orElseThrow();
        assertThat(pipeline.getTarget()).isEqualTo("ts-azure-1");
        assertThat(pipeline.getType()).isEqualTo(PipelineType.INSTALL);
        assertThat(pipeline.getRecipeDefinition()).isEqualTo(RecipeDefinition.AZURE_INSTALL_V1.name());
        assertThat(pipeline.getRequestedBy()).isEqualTo("SYSTEM");
        assertThat(pipeline.getRequestNote()).isEqualTo("4단계 진입 자동 설치");
        assertThat(installEvents.findAll()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getMessageId()).isEqualTo("msg-1");
                    assertThat(row.getTarget()).isEqualTo("ts-azure-1");
                    assertThat(row.getCloudProvider()).isEqualTo("AZURE");
                });
    }

    @Test
    void aSecondEventWhileTheRunIsActiveOnlyRecordsTheBlockingPipeline() {
        infraManager.onCloudProvider(CloudProvider.AZURE);
        InstallEvent first = handler.handle("msg-1", AZURE_INSTALL);

        InstallEvent second = handler.handle("msg-2", AZURE_INSTALL);

        assertThat(second.getOutcome()).isEqualTo(InstallEventOutcome.ALREADY_ACTIVE);
        assertThat(second.getPipelineId()).isEqualTo(first.getPipelineId());
        assertThat(pipelineRepository.count()).isEqualTo(1);
        assertThat(installEvents.count()).isEqualTo(2);
    }

    @Test
    void awsIsHeldWithoutOpeningAnything() {
        infraManager.onCloudProvider(CloudProvider.AWS);

        InstallEvent event = handler.handle("msg-1",
                """
                {"target_source_id": "ts-aws-1", "cloud_provider": "AWS", "type": "INSTALL"}""");

        assertThat(event.getOutcome()).isEqualTo(InstallEventOutcome.PROVIDER_HELD);
        assertThat(event.getPipelineId()).isNull();
        assertThat(event.getReason()).contains("AWS");
        assertThat(pipelineRepository.count()).isZero();
    }

    @Test
    void aProviderThatDisagreesWithTheCatalogIsInvalidNotInstalled() {
        infraManager.onCloudProvider(CloudProvider.IDC);   // 카탈로그는 IDC라는데 이벤트는 AZURE라고 한다

        InstallEvent event = handler.handle("msg-1", AZURE_INSTALL);

        assertThat(event.getOutcome()).isEqualTo(InstallEventOutcome.INVALID);
        assertThat(event.getReason()).contains("AZURE").contains("IDC");
        assertThat(pipelineRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "{\"cloud_provider\": \"AZURE\", \"type\": \"INSTALL\"}",
            "{\"target_source_id\": \"ts-1\", \"cloud_provider\": \"AZURE\", \"type\": \"DELETE\"}",
            "{\"target_source_id\": \"ts-1\", \"cloud_provider\": \"MARS\", \"type\": \"INSTALL\"}"})
    void aMalformedPayloadIsRecordedAsInvalid(String body) {
        infraManager.onCloudProvider(CloudProvider.AZURE);

        InstallEvent event = handler.handle("msg-1", body);

        assertThat(event.getOutcome()).isEqualTo(InstallEventOutcome.INVALID);
        assertThat(event.getReason()).isNotBlank();
        assertThat(pipelineRepository.count()).isZero();
        assertThat(installEvents.count()).isEqualTo(1);
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-09-02T00:00:00Z"));
        }

        @Bean
        FakeInfraManagerClient infraManager() {
            return new FakeInfraManagerClient();
        }

        @Bean
        PipelineSettings pipelineSettings() {
            return PipelineSettings.builder()
                    .executionTimeout(Duration.ofMinutes(50))
                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).maxTerraformPollCallErrors(10)
                    .startDelay(Duration.ZERO).build();
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
