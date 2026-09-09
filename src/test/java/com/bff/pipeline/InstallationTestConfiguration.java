package com.bff.pipeline;

import com.bff.pipeline.client.UnavailableInstallationOperationsClient;
import com.bff.pipeline.config.InstallationSettings;
import com.bff.pipeline.service.lifecycle.InstallationOperationAvailability;
import com.bff.pipeline.service.task.HttpRequestTask;
import com.bff.pipeline.service.task.TaskConfirmationInputs;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 기존 엔진 슬라이스에서도 새 HTTP mechanism을 실제 등록하되 외부 operation은 비활성으로 유지한다. */
@TestConfiguration
@Import({UnavailableInstallationOperationsClient.class, InstallationOperationAvailability.class,
        TaskConfirmationInputs.class, HttpRequestTask.class})
public class InstallationTestConfiguration {
    @Bean
    InstallationSettings installationSettings() {
        return new InstallationSettings(Set.of(), 1048576, 1048576, Duration.ofSeconds(5), 24);
    }

    @Bean
    ObjectMapper objectMapper() { return new ObjectMapper(); }
}
