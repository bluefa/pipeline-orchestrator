package com.bff.pipeline;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring: an injectable {@link Clock} (so tests control time) and the typed pipeline settings. */
@Configuration
@EnableConfigurationProperties(PipelineSettings.class)
public class PipelineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
