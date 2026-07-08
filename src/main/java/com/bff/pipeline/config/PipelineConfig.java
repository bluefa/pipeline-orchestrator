package com.bff.pipeline.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring 빈 구성 클래스다. 테스트에서 시간을 제어할 수 있도록 주입 가능한 {@link Clock} 빈을 제공하고,
 * 타입화된 설정({@link PipelineSettings} 도메인 데드라인, {@link ExecutionSettings} ADR-021 실행 파라미터,
 * {@link NotifySettings} ADR-022 종단 알림 파라미터)을 {@code @ConfigurationProperties}로 켠다.
 *
 * <p>ADR-021 실행 모델용 스레드 풀을 둘로 나눠 제공한다(데드락 회피). {@code pipelineWorkerPool}은 워커
 * drain 사이클에 쓰고, {@code infraManagerCallPool}은 호출별 타임아웃 데코레이터({@code TimeBoundedInfraManagerClient})가
 * delegate 호출을 격리 실행하는 데 쓴다. 둘 다 크기는 {@code workerPerPod}이고 컨텍스트 종료 시 shutdown된다.
 * {@code notifyRestClient}는 ADR-022 Slack webhook 전달 전용 HTTP 클라이언트다 — Boot는
 * {@code RestClient.Builder}만 자동구성하므로 {@code callTimeout}을 connect/read 타임아웃으로 건 빈을 직접
 * 만든다(호출 상한을 HTTP 클라이언트가 소유해 별도 스레드풀이 필요 없다).
 */
@Configuration
@EnableConfigurationProperties({PipelineSettings.class, ExecutionSettings.class, NotifySettings.class})
public class PipelineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService pipelineWorkerPool(ExecutionSettings settings) {
        return Executors.newFixedThreadPool(settings.workerPerPod());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService infraManagerCallPool(ExecutionSettings settings) {
        return Executors.newFixedThreadPool(settings.workerPerPod());
    }

    @Bean
    public RestClient notifyRestClient(NotifySettings settings) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int callTimeoutMillis = (int) settings.callTimeout().toMillis();
        requestFactory.setConnectTimeout(callTimeoutMillis);
        requestFactory.setReadTimeout(callTimeoutMillis);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
