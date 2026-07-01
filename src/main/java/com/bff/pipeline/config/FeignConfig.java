package com.bff.pipeline.config;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.client.InfraManagerFeignAdapter;
import com.bff.pipeline.client.InfraManagerFeignClient;
import com.bff.pipeline.client.InfraManagerOperationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * InfraManager 프로덕션 HTTP delegate 배선이다. {@code infraManagerDelegate} 빈을 만들면 {@code @Primary}
 * {@link com.bff.pipeline.client.TimeBoundedInfraManagerClient} 데코레이터가 이를 {@code @Qualifier}로 감싸 도메인에
 * 호출별 타임아웃 데코레이터를 주입한다.
 *
 * <p><b>base-url·auth-token은 필수 설정이다.</b> 도메인({@code TerraformTask}/{@code ConditionCheckTask})이
 * {@code InfraManagerClient}를 필수 주입받으므로, 애플리케이션 컨텍스트는 InfraManager 연동 없이는 애초에 뜰 수 없다.
 * 그래서 별도의 활성화 스위치(과거의 {@code @ConditionalOnProperty})를 두지 않는다 — base-url이 없으면
 * {@code @FeignClient} url 해석이, token이 비면 아래 인터셉터가 <b>시작 시점에 fail-fast</b>한다. 값은 env
 * ({@code INFRA_MANAGER_BASE_URL} / {@code INFRA_MANAGER_TOKEN})로 주입한다.
 *
 * <p>테스트(@DataJpaTest 슬라이스)는 이 설정을 컴포넌트 스캔하지 않고 fake를 직접 주입하므로 무관하다.
 * 모든 호출의 인증 헤더({@code Authorization: Bearer <token>})는 {@link #infraManagerAuth} 인터셉터가 한 곳에서 붙인다.
 */
@Configuration
@EnableFeignClients(clients = InfraManagerFeignClient.class)
public class FeignConfig {

    /**
     * 모든 InfraManager 호출에 bearer 토큰을 붙인다. 토큰이 비어 있으면 매 호출이 401로 반복 실패(→ CHECK_ERROR)하므로,
     * 배포 오설정을 조용히 넘기지 않고 시작 시점에 fail-fast한다.
     */
    @Bean
    RequestInterceptor infraManagerAuth(@Value("${infra-manager.auth-token:}") String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("infra-manager.auth-token must be set (non-blank)");
        }
        return template -> template.header("Authorization", "Bearer " + token);
    }

    @Bean("infraManagerDelegate")
    InfraManagerClient infraManagerDelegate(InfraManagerFeignClient feign, InfraManagerOperationRegistry registry,
            ObjectMapper objectMapper) {
        return new InfraManagerFeignAdapter(feign, registry, objectMapper);
    }
}
