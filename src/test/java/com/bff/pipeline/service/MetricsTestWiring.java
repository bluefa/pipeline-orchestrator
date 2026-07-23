package com.bff.pipeline.service;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.metrics.PipelineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 슬라이스 테스트용 지표 배선이다. 실행 컴포넌트(StepReporter, TaskStateMachine, PipelineControl)가
 * {@link PipelineMetrics}를 주입받으므로, 그 컴포넌트를 {@code @Import}하는 테스트는 이 클래스도 함께
 * import해서 인메모리 레지스트리와 꺼진 알림 설정을 공급한다. 테스트에서 지표 값을 확인하려면
 * {@link MeterRegistry}를 주입받아 카운터를 읽으면 된다.
 */
@TestConfiguration
public class MetricsTestWiring {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    NotifySettings notifySettings() {
        return NotifySettings.builder().build();
    }

    @Bean
    PipelineMetrics pipelineMetrics(MeterRegistry meterRegistry, PipelineRepository pipelineRepository,
            TaskRepository taskRepository, NotifySettings notifySettings, Clock clock) {
        return new PipelineMetrics(meterRegistry, pipelineRepository, taskRepository, notifySettings, clock);
    }
}
