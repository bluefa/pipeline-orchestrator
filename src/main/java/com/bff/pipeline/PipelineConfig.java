package com.bff.pipeline;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 빈 구성 클래스이다. 테스트에서 시간을 제어할 수 있도록 주입 가능한 {@link Clock} 빈을 제공하고,
 * 타입화된 파이프라인 설정({@code PipelineSettings}, {@code ExecutionSettings})을
 * {@code @ConfigurationProperties}로 활성화한다. ADR-021 실행 스케줄러({@code PipelineScheduler})는
 * {@code @PostConstruct}/{@code @PreDestroy}로 관리되는 자기-재조정 적응형 루프이므로
 * {@code @EnableScheduling}이 불필요하다.
 */
@Configuration
@EnableConfigurationProperties({PipelineSettings.class, ExecutionSettings.class})
public class PipelineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * InfraManager 호출 전용 스레드 풀이다. 외부 호출 퓨처를 실행하며, 워커 스레드가
     * 외부 호출 결과 대기 중 이 풀을 차단해도 {@code pipelineWorkerPool}을 굶기지 않는다.
     * 두 풀을 분리하여 데드락을 방지한다(ADR-021 Decision 5).
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService imCallPool(ExecutionSettings settings) {
        return Executors.newFixedThreadPool(settings.workerPerPod());
    }

    /**
     * 파이프라인 클레임/처리 사이클 전용 스레드 풀이다. 각 워커 스레드가 클레임→외부호출→리포트
     * 사이클을 실행하며, {@code imCallPool}과 분리되어 있어 상호 블로킹 없이 동작한다.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService pipelineWorkerPool(ExecutionSettings settings) {
        return Executors.newFixedThreadPool(settings.workerPerPod());
    }
}
