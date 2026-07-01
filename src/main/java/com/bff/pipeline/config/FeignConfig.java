package com.bff.pipeline.config;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.client.InfraManagerFeignAdapter;
import com.bff.pipeline.client.InfraManagerFeignClient;
import com.bff.pipeline.client.InfraManagerOperationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * InfraManager 프로덕션 HTTP delegate 배선이다. {@code infra-manager.base-url}이 설정된 컨텍스트(프로덕션)에서만
 * 이 설정 클래스 전체가 활성화된다 — {@code @EnableFeignClients}까지 클래스에 걸린 조건으로 함께 꺼지므로,
 * base-url이 없을 때 {@code @FeignClient} url 플레이스홀더 해석이 시작 시점에 실패하는 것을 막는다.
 *
 * <p>활성화되면 {@code infraManagerDelegate} 빈이 생기고, 같은 base-url 프로퍼티로
 * {@link com.bff.pipeline.client.TimeBoundedInfraManagerClient}({@code @ConditionalOnProperty("infra-manager.base-url")}
 * + {@code @Primary})가 켜져 도메인이 호출별 타임아웃 데코레이터를 주입받는다. 데코레이터는 이 delegate를 {@code @Qualifier}로
 * 주입받으므로 의존성 해석이 생성 순서를 보장한다.
 *
 * <p>테스트(@DataJpaTest 슬라이스)는 이 설정을 컴포넌트 스캔하지 않고 fake를 직접 주입하므로 delegate가 뜨지 않는다.
 * 모든 호출의 인증 헤더({@code Authorization: Bearer <token>})는 {@link #infraManagerAuth} 인터셉터가 한 곳에서 붙인다.
 */
@Configuration
@ConditionalOnProperty(prefix = "infra-manager", name = "base-url")
@EnableFeignClients(clients = InfraManagerFeignClient.class)
public class FeignConfig {

    /**
     * 모든 InfraManager 호출에 bearer 토큰을 붙인다. delegate가 켜지는 프로덕션에서 토큰이 비어 있으면 매 호출이
     * 401로 반복 실패(→ CHECK_ERROR)하므로, 배포 오설정을 조용히 넘기지 않고 시작 시점에 fail-fast한다.
     */
    @Bean
    RequestInterceptor infraManagerAuth(@Value("${infra-manager.auth-token:}") String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("infra-manager.base-url is set but infra-manager.auth-token is blank");
        }
        return template -> template.header("Authorization", "Bearer " + token);
    }

    @Bean("infraManagerDelegate")
    InfraManagerClient infraManagerDelegate(InfraManagerFeignClient feign, InfraManagerOperationRegistry registry,
            ObjectMapper objectMapper) {
        return new InfraManagerFeignAdapter(feign, registry, objectMapper);
    }
}
