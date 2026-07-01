package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * base-url이 설정된 프로덕션형 컨텍스트가 실제로 기동하고, delegate→데코레이터 체인이 형성되는지 검증한다.
 * (1) {@code FeignConfig}가 켜져 {@code infraManagerDelegate}(Feign 어댑터)가 뜨고, (2) 같은 base-url 프로퍼티로
 * {@link TimeBoundedInfraManagerClient}({@code @ConditionalOnProperty} + {@code @Primary})가 켜져 도메인이 주입받는
 * {@link InfraManagerClient}가 데코레이터인지 확인한다. base-url이 없으면 {@code @FeignClient} url 해석이 시작에서
 * 깨지므로, 이 테스트는 그 경로가 정상 기동함도 함께 증명한다.
 */
@SpringBootTest(properties = {
        "infra-manager.base-url=http://localhost:59999",
        "infra-manager.auth-token=test-token",
        // test application.yml엔 실행 블록이 없으므로(슬라이스 전용) full 컨텍스트용으로 유효한 값을 채운다.
        "pipeline.execution.worker-per-pod=2",
        "pipeline.execution.lease-duration=PT2M",
        "pipeline.execution.api-call-timeout=PT30S",
        "pipeline.execution.running-pipeline-cap=100",
        "pipeline.execution.terraform-slot-cap=20",
        "pipeline.execution.terraform-slot-retry=PT30S",
        "pipeline.execution.poll-interval=PT1S",
        "pipeline.execution.max-idle-sleep=PT5S",
        "pipeline.execution.backoff-base=PT0.2S",
        "pipeline.execution.backoff-max=PT5S",
        "pipeline.execution.jitter-ratio=0.2",
        "pipeline.execution.scheduler-initial-delay=PT1H"   // 이 테스트에선 스케줄러가 돌 필요 없음
})
class FeignDelegateWiringTest {

    @Autowired
    private InfraManagerClient injected;

    @Autowired
    @Qualifier("infraManagerDelegate")
    private InfraManagerClient delegate;

    @Test
    void domainInjectsTheTimeoutDecoratorWhichWrapsTheFeignDelegate() {
        assertThat(injected).isInstanceOf(TimeBoundedInfraManagerClient.class);
        assertThat(delegate).isInstanceOf(InfraManagerFeignAdapter.class);
    }
}
