package com.bff.pipeline.client;

import com.bff.pipeline.dto.ApplyJobStatusResponse;
import com.bff.pipeline.dto.ApplyNetworkResponse;
import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.DestroyJobStatusResponse;
import com.bff.pipeline.dto.DestroyNetworkResponse;
import com.bff.pipeline.dto.NetworkReadyResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * InfraManager HTTP API의 raw 전송 인터페이스다(Feign 프록시). 각 메서드는 InfraManager의 <b>구체적인 실제 API</b> 하나에
 * 1:1로 대응한다 — operation마다 경로도, 응답 형태도 다르므로 operation별 메서드·응답 DTO를 각각 둔다. 어느 API를 부를지
 * 고르는 라우팅과 operation별 응답 → 도메인 공통 형식 변환은 여기가 아니라 {@link InfraManagerFeignAdapter}가 소유한다.
 * 인증 헤더({@code Authorization: Bearer ...})는 {@code FeignConfig}의 {@code RequestInterceptor}가 모든 호출에 붙인다.
 *
 * <p>base url·타임아웃은 {@code application.yml}의 {@code infra-manager.*} / {@code spring.cloud.openfeign.*}에서
 * 바인딩된다. 이 빈은 실제 delegate가 필요한 프로덕션 컨텍스트에서만 뜬다(테스트는 fake를 직접 주입).
 *
 * <p>⚠️ 경로/스키마는 실제 InfraManager API 확정 전 가정값이다. 확정 시 이 인터페이스의 메서드·경로·응답 DTO만 조정한다.
 */
@FeignClient(name = "infra-manager", url = "${infra-manager.base-url}")
public interface InfraManagerFeignClient {

    // ── TERRAFORM_JOB dispatch: operation마다 다른 API·응답 ──

    @PostMapping("/infra/network/apply")
    ApplyNetworkResponse applyNetwork(@RequestParam("target") String target);

    @PostMapping("/infra/network/destroy")
    DestroyNetworkResponse destroyNetwork(@RequestParam("target") String target);

    // ── TERRAFORM_JOB status: operation마다 다른 API·응답 ──

    @GetMapping("/infra/network/apply/jobs/{jobId}")
    ApplyJobStatusResponse applyJobStatus(@PathVariable("jobId") String jobId);

    @GetMapping("/infra/network/destroy/jobs/{jobId}")
    DestroyJobStatusResponse destroyJobStatus(@PathVariable("jobId") String jobId);

    // ── CONDITION_CHECK: operation마다 다른 API·응답 ──

    @GetMapping("/infra/network/ready")
    NetworkReadyResponse networkReady(@RequestParam("target") String target);

    // ── 기타 ──

    @GetMapping("/infra/targets/{target}/cloud-provider")
    CloudProviderResponse cloudProvider(@PathVariable("target") String target);
}
