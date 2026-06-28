package com.bff.pipeline.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring: an injectable {@link Clock} (so tests control time) and the bounded pool that caps concurrent InfraManager calls. */
@Configuration
@EnableConfigurationProperties(PipelineSettings.class)
public class PipelineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService imCallPool(PipelineSettings settings) {
        return Executors.newFixedThreadPool(settings.workerPoolSize());
    }
}
