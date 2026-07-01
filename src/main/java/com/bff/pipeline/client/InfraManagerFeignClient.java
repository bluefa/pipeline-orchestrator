package com.bff.pipeline.client;

import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.ConditionResponse;
import com.bff.pipeline.dto.TerraformDispatchResponse;
import com.bff.pipeline.dto.TerraformStatusResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * InfraManager HTTP API의 raw 전송 인터페이스다(Feign 프록시). 각 메서드는 InfraManager의 <b>구체적인 실제 API</b> 하나에
 * 1:1로 대응한다 — operation에 따라 어느 API를 부를지 고르는 라우팅은 여기가 아니라 {@link InfraManagerFeignAdapter}가
 * 소유한다. 이 인터페이스는 경로·요청·응답 DTO만 있는 그대로 다루며 예외 번역/계약 방어는 하지 않는다.
 * 인증 헤더({@code Authorization: Bearer ...})는 {@code FeignConfig}의 {@code RequestInterceptor}가 모든 호출에 붙인다.
 *
 * <p>base url·타임아웃은 {@code application.yml}의 {@code infra-manager.*} / {@code spring.cloud.openfeign.*}에서
 * 바인딩된다. 이 빈은 실제 delegate가 필요한 프로덕션 컨텍스트에서만 뜬다(테스트는 fake를 직접 주입).
 *
 * <p>⚠️ 경로/스키마는 실제 InfraManager API 확정 전 가정값이다. 확정 시 이 인터페이스의 메서드·경로만 조정하면 된다.
 */
@FeignClient(name = "infra-manager", url = "${infra-manager.base-url}")
public interface InfraManagerFeignClient {

    // ── TERRAFORM_JOB dispatch: operation마다 다른 실제 API ──

    /** APPLY_NETWORK: 네트워크 인프라 구성(apply) 잡을 던진다. */
    @PostMapping("/infra/network/apply")
    TerraformDispatchResponse applyNetwork(@RequestParam("target") String target);

    /** DESTROY_NETWORK: 네트워크 인프라 철거(destroy) 잡을 던진다. */
    @PostMapping("/infra/network/destroy")
    TerraformDispatchResponse destroyNetwork(@RequestParam("target") String target);

    /** APPLY_NETWORK 잡 상태를 job id로 조회한다. */
    @GetMapping("/infra/network/apply/jobs/{jobId}")
    TerraformStatusResponse applyJobStatus(@PathVariable("jobId") String jobId);

    /** DESTROY_NETWORK 잡 상태를 job id로 조회한다. */
    @GetMapping("/infra/network/destroy/jobs/{jobId}")
    TerraformStatusResponse destroyJobStatus(@PathVariable("jobId") String jobId);

    // ── CONDITION_CHECK: operation마다 다른 실제 API ──

    /** NETWORK_READY: 네트워크 준비 여부를 한 번 탐색한다. */
    @GetMapping("/infra/network/ready")
    ConditionResponse networkReady(@RequestParam("target") String target);

    // ── 기타 ──

    /** targetSourceId의 cloud provider를 조회한다. */
    @GetMapping("/infra/targets/{target}/cloud-provider")
    CloudProviderResponse cloudProvider(@PathVariable("target") String target);
}
