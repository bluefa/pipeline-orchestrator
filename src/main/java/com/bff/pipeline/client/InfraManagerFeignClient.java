package com.bff.pipeline.client;

import com.bff.pipeline.client.terraform.IdcTerraformType;
import com.bff.pipeline.client.terraform.TerraformJobType;
import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.DispatchedJob;
import com.bff.pipeline.dto.NetworkReadyResponse;
import com.bff.pipeline.dto.TerraformJobStatusResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * InfraManager HTTP API의 raw 전송 인터페이스다(Feign 프록시). Terraform 계열은 확정된 실 API 명세
 * (docs/terraform-client-and-postcheck-design.md §1)와 1:1이다 — 경로·HTTP 메서드·쿼리 파라미터가 패밀리마다
 * 다르지만 응답 shape은 셋으로 수렴한다: dispatch는 {@link DispatchedJob}(단건) 또는 그 목록, job 조회는
 * {@link TerraformJobStatusResponse}, result는 {@code String}.
 *
 * operation별 구분은 default 메서드가 소유한다. {@code jobType}/{@code idcTerraformType}처럼 operation이
 * 결정하는 상수는 호출부(카탈로그)로 흘리지 않고 이 인터페이스 안에서 default 메서드로 닫는다 — 카탈로그
 * ({@code TerraformBindingCatalog})의 행은 리터럴 인자 없는 순수 메서드 참조만 갖는다. default 메서드는 Feign
 * 프록시 위에서 in-JVM으로 raw 메서드에 위임할 뿐 HTTP 계약을 추가하지 않는다.
 *
 * 어느 API를 부를지 고르는 라우팅과 응답 → 도메인 공통 형식 변환은 여기가 아니라 카탈로그 바인딩이 소유한다.
 * 인증 헤더({@code Authorization: Bearer ...})는 {@code FeignConfig}의 {@code RequestInterceptor}가 모든 호출에
 * 붙인다. base url·타임아웃은 {@code application.yml}의 {@code infra-manager.*} / {@code spring.cloud.openfeign.*}.
 *
 * ⚠️ CONDITION_CHECK({@code networkReady})와 {@code cloudProvider}는 실제 API 미확정 — 여전히 가정 경로다.
 */
@FeignClient(name = "infra-manager", url = "${infra-manager.base-url}")
public interface InfraManagerFeignClient {

    // ══ AWS 서비스 Terraform — 경로 분리형 dispatch, 목록 응답, 조회 파라미터 없음 ══

    @PostMapping("/infra/target-sources/{targetSourceId}/terraform-jobs/plan")
    List<DispatchedJob> awsServicePlan(@PathVariable("targetSourceId") String targetSourceId);

    @PostMapping("/infra/target-sources/{targetSourceId}/terraform-jobs/apply")
    List<DispatchedJob> awsServiceApply(@PathVariable("targetSourceId") String targetSourceId);

    @DeleteMapping("/infra/target-sources/{targetSourceId}/terraform-jobs/destroy")
    List<DispatchedJob> awsServiceDestroy(@PathVariable("targetSourceId") String targetSourceId);

    @GetMapping("/infra/terraform-jobs/plan/{terraformJobId}")
    TerraformJobStatusResponse awsServicePlanJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/terraform-jobs/apply/{terraformJobId}")
    TerraformJobStatusResponse awsServiceApplyJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/terraform-jobs/destroy/{terraformJobId}")
    TerraformJobStatusResponse awsServiceDestroyJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/terraform-jobs/plan/{terraformJobId}/result")
    String awsServicePlanJobResult(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/terraform-jobs/apply/{terraformJobId}/result")
    String awsServiceApplyJobResult(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/terraform-jobs/destroy/{terraformJobId}/result")
    String awsServiceDestroyJobResult(@PathVariable("terraformJobId") String terraformJobId);

    // ══ AWS BDC Service Level Common Terraform — 경로 분리형 dispatch(전부 POST), 단건 응답, 조회는 jobType ══

    @PostMapping("/infra/target-sources/{targetSourceId}/aws/service/level/common/terraform/plan")
    DispatchedJob awsBdcCommonPlan(@PathVariable("targetSourceId") String targetSourceId);

    @PostMapping("/infra/target-sources/{targetSourceId}/aws/service/level/common/terraform/apply")
    DispatchedJob awsBdcCommonApply(@PathVariable("targetSourceId") String targetSourceId);

    @PostMapping("/infra/target-sources/{targetSourceId}/aws/service/level/common/terraform/destroy")
    DispatchedJob awsBdcCommonDestroy(@PathVariable("targetSourceId") String targetSourceId);

    @GetMapping("/infra/aws/service/level/common/terraform/{terraformJobId}")
    TerraformJobStatusResponse awsBdcCommonJobStatus(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/aws/service/level/common/terraform/{terraformJobId}/result")
    String awsBdcCommonJobResult(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    default TerraformJobStatusResponse awsBdcCommonPlanJobStatus(String terraformJobId) {
        return awsBdcCommonJobStatus(terraformJobId, TerraformJobType.PLAN);
    }

    default TerraformJobStatusResponse awsBdcCommonApplyJobStatus(String terraformJobId) {
        return awsBdcCommonJobStatus(terraformJobId, TerraformJobType.APPLY);
    }

    default TerraformJobStatusResponse awsBdcCommonDestroyJobStatus(String terraformJobId) {
        return awsBdcCommonJobStatus(terraformJobId, TerraformJobType.DESTROY);
    }

    default String awsBdcCommonPlanJobResult(String terraformJobId) {
        return awsBdcCommonJobResult(terraformJobId, TerraformJobType.PLAN);
    }

    default String awsBdcCommonApplyJobResult(String terraformJobId) {
        return awsBdcCommonJobResult(terraformJobId, TerraformJobType.APPLY);
    }

    default String awsBdcCommonDestroyJobResult(String terraformJobId) {
        return awsBdcCommonJobResult(terraformJobId, TerraformJobType.DESTROY);
    }

    // ══ AWS BDC Service Level Terraform — plan 전용 조회 경로 / apply·destroy는 "action" 공용 경로 + jobType ══

    @PostMapping("/infra/target-sources/{targetSourceId}/bdc-service-level-terraform-jobs/plan")
    DispatchedJob awsBdcServiceLevelPlan(@PathVariable("targetSourceId") String targetSourceId);

    @PostMapping("/infra/target-sources/{targetSourceId}/bdc-service-level-terraform-jobs/apply")
    DispatchedJob awsBdcServiceLevelApply(@PathVariable("targetSourceId") String targetSourceId);

    /** destroy의 실 경로명은 "remove"다(스펙 그대로). */
    @DeleteMapping("/infra/target-sources/{targetSourceId}/bdc-service-level-terraform-jobs/remove")
    DispatchedJob awsBdcServiceLevelDestroy(@PathVariable("targetSourceId") String targetSourceId);

    @GetMapping("/infra/bdc-service-level-terraform-jobs/plan/{terraformJobId}")
    TerraformJobStatusResponse awsBdcServiceLevelPlanJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/bdc-service-level-terraform-jobs/plan/{terraformJobId}/result")
    String awsBdcServiceLevelPlanJobResult(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/bdc-service-level-terraform-jobs/action/{terraformJobId}")
    TerraformJobStatusResponse awsBdcServiceLevelActionJobStatus(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/bdc-service-level-terraform-jobs/action/{terraformJobId}/result")
    String awsBdcServiceLevelActionJobResult(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    default TerraformJobStatusResponse awsBdcServiceLevelApplyJobStatus(String terraformJobId) {
        return awsBdcServiceLevelActionJobStatus(terraformJobId, TerraformJobType.APPLY);
    }

    default TerraformJobStatusResponse awsBdcServiceLevelDestroyJobStatus(String terraformJobId) {
        return awsBdcServiceLevelActionJobStatus(terraformJobId, TerraformJobType.DESTROY);
    }

    default String awsBdcServiceLevelApplyJobResult(String terraformJobId) {
        return awsBdcServiceLevelActionJobResult(terraformJobId, TerraformJobType.APPLY);
    }

    default String awsBdcServiceLevelDestroyJobResult(String terraformJobId) {
        return awsBdcServiceLevelActionJobResult(terraformJobId, TerraformJobType.DESTROY);
    }

    // ══ GCP 서비스 Terraform — 단일 엔드포인트 + jobType, 목록 응답 ══

    @PostMapping("/infra/target-sources/{targetSourceId}/gcp/service-terraform-jobs")
    List<DispatchedJob> gcpServiceDispatch(@PathVariable("targetSourceId") String targetSourceId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/gcp/service-terraform-jobs/{terraformJobId}")
    TerraformJobStatusResponse gcpServiceJobStatus(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/gcp/service-terraform-jobs/{terraformJobId}/result")
    String gcpServiceJobResult(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    default List<DispatchedJob> gcpServicePlan(String targetSourceId) {
        return gcpServiceDispatch(targetSourceId, TerraformJobType.PLAN);
    }

    default List<DispatchedJob> gcpServiceApply(String targetSourceId) {
        return gcpServiceDispatch(targetSourceId, TerraformJobType.APPLY);
    }

    default List<DispatchedJob> gcpServiceDestroy(String targetSourceId) {
        return gcpServiceDispatch(targetSourceId, TerraformJobType.DESTROY);
    }

    default TerraformJobStatusResponse gcpServicePlanJobStatus(String terraformJobId) {
        return gcpServiceJobStatus(terraformJobId, TerraformJobType.PLAN);
    }

    default TerraformJobStatusResponse gcpServiceApplyJobStatus(String terraformJobId) {
        return gcpServiceJobStatus(terraformJobId, TerraformJobType.APPLY);
    }

    default TerraformJobStatusResponse gcpServiceDestroyJobStatus(String terraformJobId) {
        return gcpServiceJobStatus(terraformJobId, TerraformJobType.DESTROY);
    }

    default String gcpServicePlanJobResult(String terraformJobId) {
        return gcpServiceJobResult(terraformJobId, TerraformJobType.PLAN);
    }

    default String gcpServiceApplyJobResult(String terraformJobId) {
        return gcpServiceJobResult(terraformJobId, TerraformJobType.APPLY);
    }

    default String gcpServiceDestroyJobResult(String terraformJobId) {
        return gcpServiceJobResult(terraformJobId, TerraformJobType.DESTROY);
    }

    // ══ GCP BDC Terraform — 단일 엔드포인트 + jobType, 목록 응답 ══

    @PostMapping("/infra/target-sources/{targetSourceId}/gcp/bdc-terraform-jobs")
    List<DispatchedJob> gcpBdcDispatch(@PathVariable("targetSourceId") String targetSourceId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/gcp/bdc-terraform-jobs/{terraformJobId}")
    TerraformJobStatusResponse gcpBdcJobStatus(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/gcp/bdc-terraform-jobs/{terraformJobId}/result")
    String gcpBdcJobResult(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    default List<DispatchedJob> gcpBdcPlan(String targetSourceId) {
        return gcpBdcDispatch(targetSourceId, TerraformJobType.PLAN);
    }

    default List<DispatchedJob> gcpBdcApply(String targetSourceId) {
        return gcpBdcDispatch(targetSourceId, TerraformJobType.APPLY);
    }

    default List<DispatchedJob> gcpBdcDestroy(String targetSourceId) {
        return gcpBdcDispatch(targetSourceId, TerraformJobType.DESTROY);
    }

    default TerraformJobStatusResponse gcpBdcPlanJobStatus(String terraformJobId) {
        return gcpBdcJobStatus(terraformJobId, TerraformJobType.PLAN);
    }

    default TerraformJobStatusResponse gcpBdcApplyJobStatus(String terraformJobId) {
        return gcpBdcJobStatus(terraformJobId, TerraformJobType.APPLY);
    }

    default TerraformJobStatusResponse gcpBdcDestroyJobStatus(String terraformJobId) {
        return gcpBdcJobStatus(terraformJobId, TerraformJobType.DESTROY);
    }

    default String gcpBdcPlanJobResult(String terraformJobId) {
        return gcpBdcJobResult(terraformJobId, TerraformJobType.PLAN);
    }

    default String gcpBdcApplyJobResult(String terraformJobId) {
        return gcpBdcJobResult(terraformJobId, TerraformJobType.APPLY);
    }

    default String gcpBdcDestroyJobResult(String terraformJobId) {
        return gcpBdcJobResult(terraformJobId, TerraformJobType.DESTROY);
    }

    // ══ Azure BDC Terraform — 경로 분리형, 목록 응답, 조회 파라미터 없음 (destroy dispatch만 /azure prefix 없음 — 스펙 그대로) ══

    @PostMapping("/infra/azure/target-sources/{targetSourceId}/azure/terraform-jobs/plan")
    List<DispatchedJob> azureBdcPlan(@PathVariable("targetSourceId") String targetSourceId);

    @PostMapping("/infra/azure/target-sources/{targetSourceId}/azure/terraform-jobs/apply")
    List<DispatchedJob> azureBdcApply(@PathVariable("targetSourceId") String targetSourceId);

    @DeleteMapping("/infra/target-sources/{targetSourceId}/azure/terraform-jobs/destroy")
    List<DispatchedJob> azureBdcDestroy(@PathVariable("targetSourceId") String targetSourceId);

    @GetMapping("/infra/azure/terraform-jobs/plan/{terraformJobId}")
    TerraformJobStatusResponse azureBdcPlanJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/azure/terraform-jobs/apply/{terraformJobId}")
    TerraformJobStatusResponse azureBdcApplyJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/azure/terraform-jobs/destroy/{terraformJobId}")
    TerraformJobStatusResponse azureBdcDestroyJobStatus(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/azure/terraform-jobs/plan/{terraformJobId}/result")
    String azureBdcPlanJobResult(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/azure/terraform-jobs/apply/{terraformJobId}/result")
    String azureBdcApplyJobResult(@PathVariable("terraformJobId") String terraformJobId);

    @GetMapping("/infra/azure/terraform-jobs/destroy/{terraformJobId}/result")
    String azureBdcDestroyJobResult(@PathVariable("terraformJobId") String terraformJobId);

    // ══ IDC Terraform (CX/BDP) — 단일 action 엔드포인트 + jobType + idcTerraformType, 단건 응답 ══

    @PostMapping("/infra/target-sources/{targetSourceId}/idc/terraform/action")
    DispatchedJob idcDispatch(@PathVariable("targetSourceId") String targetSourceId,
            @RequestParam("jobType") TerraformJobType jobType,
            @RequestParam("idcTerraformType") IdcTerraformType idcTerraformType);

    @GetMapping("/infra/idc/terraform/{terraformJobId}")
    TerraformJobStatusResponse idcJobStatus(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    @GetMapping("/infra/idc/terraform/{terraformJobId}/result")
    String idcJobResult(@PathVariable("terraformJobId") String terraformJobId,
            @RequestParam("jobType") TerraformJobType jobType);

    default DispatchedJob idcCxPlan(String targetSourceId) {
        return idcDispatch(targetSourceId, TerraformJobType.PLAN, IdcTerraformType.CX);
    }

    default DispatchedJob idcCxApply(String targetSourceId) {
        return idcDispatch(targetSourceId, TerraformJobType.APPLY, IdcTerraformType.CX);
    }

    default DispatchedJob idcCxDestroy(String targetSourceId) {
        return idcDispatch(targetSourceId, TerraformJobType.DESTROY, IdcTerraformType.CX);
    }

    default DispatchedJob idcBdpPlan(String targetSourceId) {
        return idcDispatch(targetSourceId, TerraformJobType.PLAN, IdcTerraformType.BDP);
    }

    default DispatchedJob idcBdpApply(String targetSourceId) {
        return idcDispatch(targetSourceId, TerraformJobType.APPLY, IdcTerraformType.BDP);
    }

    default DispatchedJob idcBdpDestroy(String targetSourceId) {
        return idcDispatch(targetSourceId, TerraformJobType.DESTROY, IdcTerraformType.BDP);
    }

    default TerraformJobStatusResponse idcPlanJobStatus(String terraformJobId) {
        return idcJobStatus(terraformJobId, TerraformJobType.PLAN);
    }

    default TerraformJobStatusResponse idcApplyJobStatus(String terraformJobId) {
        return idcJobStatus(terraformJobId, TerraformJobType.APPLY);
    }

    default TerraformJobStatusResponse idcDestroyJobStatus(String terraformJobId) {
        return idcJobStatus(terraformJobId, TerraformJobType.DESTROY);
    }

    default String idcPlanJobResult(String terraformJobId) {
        return idcJobResult(terraformJobId, TerraformJobType.PLAN);
    }

    default String idcApplyJobResult(String terraformJobId) {
        return idcJobResult(terraformJobId, TerraformJobType.APPLY);
    }

    default String idcDestroyJobResult(String terraformJobId) {
        return idcJobResult(terraformJobId, TerraformJobType.DESTROY);
    }

    // ══ CONDITION_CHECK / 기타 — 실제 API 미확정(가정 경로) ══

    @GetMapping("/infra/network/ready")
    NetworkReadyResponse networkReady(@RequestParam("target") String target);

    @GetMapping("/infra/targets/{target}/cloud-provider")
    CloudProviderResponse cloudProvider(@PathVariable("target") String target);
}
