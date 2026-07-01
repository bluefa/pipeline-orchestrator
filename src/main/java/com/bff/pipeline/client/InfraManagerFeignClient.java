package com.bff.pipeline.client;

import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.ConditionResponse;
import com.bff.pipeline.dto.TerraformDispatchResponse;
import com.bff.pipeline.dto.TerraformStatusResponse;
import com.bff.pipeline.enums.TaskOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * InfraManager HTTP API의 raw 전송 인터페이스다(Feign 프록시). 상태 코드·응답 DTO를 있는 그대로 다루며,
 * 예외 번역·계약 방어는 하지 않는다 — 그 책임은 이 클라이언트를 감싸는 {@link InfraManagerFeignAdapter}에 있다.
 * 인증 헤더({@code Authorization: Bearer ...})는 {@code FeignConfig}의 {@code RequestInterceptor}가 모든 호출에 붙인다.
 *
 * <p>base url·타임아웃은 {@code application.yml}의 {@code infra-manager.*} / {@code spring.cloud.openfeign.*}에서
 * 바인딩된다. 이 빈은 실제 delegate가 필요한 프로덕션 컨텍스트에서만 뜬다(테스트는 fake를 직접 주입).
 */
@FeignClient(name = "infra-manager", url = "${infra-manager.base-url}")
public interface InfraManagerFeignClient {

    @PostMapping("/infra/terraform/{operation}")
    TerraformDispatchResponse runTerraform(@PathVariable("operation") TaskOperation operation,
            @RequestParam("target") String target);

    @GetMapping("/infra/terraform/jobs/{jobId}")
    TerraformStatusResponse terraformJobStatus(@PathVariable("jobId") String jobId);

    @GetMapping("/infra/conditions/{operation}")
    ConditionResponse checkCondition(@PathVariable("operation") TaskOperation operation,
            @RequestParam("target") String target);

    @GetMapping("/infra/targets/{target}/cloud-provider")
    CloudProviderResponse cloudProvider(@PathVariable("target") String target);
}
