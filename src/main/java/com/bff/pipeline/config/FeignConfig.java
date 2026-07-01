package com.bff.pipeline.config;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.client.InfraManagerFeignAdapter;
import com.bff.pipeline.client.InfraManagerFeignClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * InfraManager 프로덕션 HTTP delegate 배선이다. {@code infra-manager.base-url}이 설정된 컨텍스트(프로덕션)에서만
 * delegate가 뜨고, 그러면 기존 {@link com.bff.pipeline.client.TimeBoundedInfraManagerClient}
 * ({@code @ConditionalOnBean("infraManagerDelegate")} + {@code @Primary})가 활성화되어 도메인이 데코레이터를 주입받는다.
 *
 * <p>테스트(@DataJpaTest 슬라이스)는 이 설정을 컴포넌트 스캔하지 않고 fake를 직접 주입하므로 delegate가 뜨지 않는다.
 * base-url이 없으면 로컬/데모 기동도 delegate 없이 정상 동작한다.
 *
 * <p>모든 호출의 인증 헤더({@code Authorization: Bearer <token>})는 {@link #infraManagerAuth} 인터셉터가 한 곳에서 붙인다.
 */
@Configuration
@EnableFeignClients(clients = InfraManagerFeignClient.class)
public class FeignConfig {

    @Bean
    RequestInterceptor infraManagerAuth(@Value("${infra-manager.auth-token:}") String token) {
        return template -> template.header("Authorization", "Bearer " + token);
    }

    @Bean("infraManagerDelegate")
    @ConditionalOnProperty(prefix = "infra-manager", name = "base-url")
    InfraManagerClient infraManagerDelegate(InfraManagerFeignClient feign, ObjectMapper objectMapper) {
        return new InfraManagerFeignAdapter(feign, objectMapper);
    }
}
