package com.bff.pipeline;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 빈 구성 클래스이다. 테스트에서 시간을 제어할 수 있도록 주입 가능한 {@link Clock} 빈을 제공하고,
 * 타입화된 파이프라인 설정({@code PipelineSettings})을 {@code @ConfigurationProperties}로 활성화한다.
 */
@Configuration
@EnableConfigurationProperties(PipelineSettings.class)
public class PipelineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
