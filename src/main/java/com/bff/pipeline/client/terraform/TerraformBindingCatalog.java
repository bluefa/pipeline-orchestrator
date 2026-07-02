package com.bff.pipeline.client.terraform;

import static com.bff.pipeline.enums.TaskOperation.AWS_BDC_COMMON_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.AWS_BDC_COMMON_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.AWS_BDC_COMMON_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.AWS_SERVICE_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.AWS_SERVICE_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.AWS_SERVICE_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.AZURE_BDC_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.AZURE_BDC_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.AZURE_BDC_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.GCP_BDC_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.GCP_BDC_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.GCP_BDC_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.GCP_SERVICE_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.GCP_SERVICE_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.GCP_SERVICE_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.IDC_BDP_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.IDC_BDP_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.IDC_BDP_TF_PLAN;
import static com.bff.pipeline.enums.TaskOperation.IDC_CX_TF_APPLY;
import static com.bff.pipeline.enums.TaskOperation.IDC_CX_TF_DESTROY;
import static com.bff.pipeline.enums.TaskOperation.IDC_CX_TF_PLAN;

import com.bff.pipeline.client.InfraManagerFeignClient;
import com.bff.pipeline.dto.DispatchedJob;
import com.bff.pipeline.dto.TerraformJobStatusResponse;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TERRAFORM_JOB operation ↔ InfraManager 실 API의 바인딩 카탈로그다 — 이 파일 한 화면이 API 스펙 표와 1:1로
 * 대응한다(설계 docs/terraform-client-and-postcheck-design.md §2 설계 2). 행 하나 = operation 하나 = @Bean 하나이며,
 * 행은 리터럴 인자 없는 메서드 참조만 갖는다 — {@code jobType} 같은 operation-결정 상수는
 * {@link InfraManagerFeignClient}의 default 메서드 안에 닫혀 있다. 공통 변환(단건→목록 승격, job id 방어,
 * 폴 정규화)은 {@link CatalogTerraformBinding} 하나가 소유한다.
 *
 * 새 operation 추가 = TaskOperation 값 + Feign 메서드(2~3개) + 여기 행 하나. 행을 빠뜨리면
 * {@code InfraManagerOperationRegistry}가 부팅/CI에서 실패시킨다. operation별 방어 로직이 필요해지면 그 행만
 * 별도 {@link TerraformOperationBinding} 구현 클래스로 꺼내면 된다(같은 인터페이스라 공존).
 */
@Configuration
public class TerraformBindingCatalog {

    // ── AWS 서비스 ──

    @Bean
    TerraformOperationBinding awsServiceTfPlan(InfraManagerFeignClient feignClient) {
        return row(AWS_SERVICE_TF_PLAN, TerraformJobType.PLAN,
                feignClient::awsServicePlan, feignClient::awsServicePlanJobStatus, feignClient::awsServicePlanJobResult);
    }

    @Bean
    TerraformOperationBinding awsServiceTfApply(InfraManagerFeignClient feignClient) {
        return row(AWS_SERVICE_TF_APPLY, TerraformJobType.APPLY,
                feignClient::awsServiceApply, feignClient::awsServiceApplyJobStatus, feignClient::awsServiceApplyJobResult);
    }

    @Bean
    TerraformOperationBinding awsServiceTfDestroy(InfraManagerFeignClient feignClient) {
        return row(AWS_SERVICE_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::awsServiceDestroy, feignClient::awsServiceDestroyJobStatus, feignClient::awsServiceDestroyJobResult);
    }

    // ── AWS BDC Service Level Common ──

    @Bean
    TerraformOperationBinding awsBdcCommonTfPlan(InfraManagerFeignClient feignClient) {
        return rowSingle(AWS_BDC_COMMON_TF_PLAN, TerraformJobType.PLAN,
                feignClient::awsBdcCommonPlan, feignClient::awsBdcCommonPlanJobStatus, feignClient::awsBdcCommonPlanJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcCommonTfApply(InfraManagerFeignClient feignClient) {
        return rowSingle(AWS_BDC_COMMON_TF_APPLY, TerraformJobType.APPLY,
                feignClient::awsBdcCommonApply, feignClient::awsBdcCommonApplyJobStatus, feignClient::awsBdcCommonApplyJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcCommonTfDestroy(InfraManagerFeignClient feignClient) {
        return rowSingle(AWS_BDC_COMMON_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::awsBdcCommonDestroy, feignClient::awsBdcCommonDestroyJobStatus, feignClient::awsBdcCommonDestroyJobResult);
    }

    // ── AWS BDC Service Level ──

    @Bean
    TerraformOperationBinding awsBdcServiceLevelTfPlan(InfraManagerFeignClient feignClient) {
        return rowSingle(AWS_BDC_SERVICE_LEVEL_TF_PLAN, TerraformJobType.PLAN,
                feignClient::awsBdcServiceLevelPlan, feignClient::awsBdcServiceLevelPlanJobStatus, feignClient::awsBdcServiceLevelPlanJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcServiceLevelTfApply(InfraManagerFeignClient feignClient) {
        return rowSingle(AWS_BDC_SERVICE_LEVEL_TF_APPLY, TerraformJobType.APPLY,
                feignClient::awsBdcServiceLevelApply, feignClient::awsBdcServiceLevelApplyJobStatus, feignClient::awsBdcServiceLevelApplyJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcServiceLevelTfDestroy(InfraManagerFeignClient feignClient) {
        return rowSingle(AWS_BDC_SERVICE_LEVEL_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::awsBdcServiceLevelDestroy, feignClient::awsBdcServiceLevelDestroyJobStatus,
                feignClient::awsBdcServiceLevelDestroyJobResult);
    }

    // ── GCP 서비스 ──

    @Bean
    TerraformOperationBinding gcpServiceTfPlan(InfraManagerFeignClient feignClient) {
        return row(GCP_SERVICE_TF_PLAN, TerraformJobType.PLAN,
                feignClient::gcpServicePlan, feignClient::gcpServicePlanJobStatus, feignClient::gcpServicePlanJobResult);
    }

    @Bean
    TerraformOperationBinding gcpServiceTfApply(InfraManagerFeignClient feignClient) {
        return row(GCP_SERVICE_TF_APPLY, TerraformJobType.APPLY,
                feignClient::gcpServiceApply, feignClient::gcpServiceApplyJobStatus, feignClient::gcpServiceApplyJobResult);
    }

    @Bean
    TerraformOperationBinding gcpServiceTfDestroy(InfraManagerFeignClient feignClient) {
        return row(GCP_SERVICE_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::gcpServiceDestroy, feignClient::gcpServiceDestroyJobStatus, feignClient::gcpServiceDestroyJobResult);
    }

    // ── GCP BDC ──

    @Bean
    TerraformOperationBinding gcpBdcTfPlan(InfraManagerFeignClient feignClient) {
        return row(GCP_BDC_TF_PLAN, TerraformJobType.PLAN,
                feignClient::gcpBdcPlan, feignClient::gcpBdcPlanJobStatus, feignClient::gcpBdcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding gcpBdcTfApply(InfraManagerFeignClient feignClient) {
        return row(GCP_BDC_TF_APPLY, TerraformJobType.APPLY,
                feignClient::gcpBdcApply, feignClient::gcpBdcApplyJobStatus, feignClient::gcpBdcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding gcpBdcTfDestroy(InfraManagerFeignClient feignClient) {
        return row(GCP_BDC_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::gcpBdcDestroy, feignClient::gcpBdcDestroyJobStatus, feignClient::gcpBdcDestroyJobResult);
    }

    // ── Azure BDC ──

    @Bean
    TerraformOperationBinding azureBdcTfPlan(InfraManagerFeignClient feignClient) {
        return row(AZURE_BDC_TF_PLAN, TerraformJobType.PLAN,
                feignClient::azureBdcPlan, feignClient::azureBdcPlanJobStatus, feignClient::azureBdcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding azureBdcTfApply(InfraManagerFeignClient feignClient) {
        return row(AZURE_BDC_TF_APPLY, TerraformJobType.APPLY,
                feignClient::azureBdcApply, feignClient::azureBdcApplyJobStatus, feignClient::azureBdcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding azureBdcTfDestroy(InfraManagerFeignClient feignClient) {
        return row(AZURE_BDC_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::azureBdcDestroy, feignClient::azureBdcDestroyJobStatus, feignClient::azureBdcDestroyJobResult);
    }

    // ── IDC CX / BDP (status·result 경로는 CX/BDP 공용 — 스펙 그대로) ──

    @Bean
    TerraformOperationBinding idcCxTfPlan(InfraManagerFeignClient feignClient) {
        return rowSingle(IDC_CX_TF_PLAN, TerraformJobType.PLAN,
                feignClient::idcCxPlan, feignClient::idcPlanJobStatus, feignClient::idcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding idcCxTfApply(InfraManagerFeignClient feignClient) {
        return rowSingle(IDC_CX_TF_APPLY, TerraformJobType.APPLY,
                feignClient::idcCxApply, feignClient::idcApplyJobStatus, feignClient::idcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding idcCxTfDestroy(InfraManagerFeignClient feignClient) {
        return rowSingle(IDC_CX_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::idcCxDestroy, feignClient::idcDestroyJobStatus, feignClient::idcDestroyJobResult);
    }

    @Bean
    TerraformOperationBinding idcBdpTfPlan(InfraManagerFeignClient feignClient) {
        return rowSingle(IDC_BDP_TF_PLAN, TerraformJobType.PLAN,
                feignClient::idcBdpPlan, feignClient::idcPlanJobStatus, feignClient::idcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding idcBdpTfApply(InfraManagerFeignClient feignClient) {
        return rowSingle(IDC_BDP_TF_APPLY, TerraformJobType.APPLY,
                feignClient::idcBdpApply, feignClient::idcApplyJobStatus, feignClient::idcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding idcBdpTfDestroy(InfraManagerFeignClient feignClient) {
        return rowSingle(IDC_BDP_TF_DESTROY, TerraformJobType.DESTROY,
                feignClient::idcBdpDestroy, feignClient::idcDestroyJobStatus, feignClient::idcDestroyJobResult);
    }

    /**
     * 테스트용 — 카탈로그 전 행을 한 번에 만든다. registry 생성자의 완전성 검증과 결합하면 "행을 여기 빠뜨림"이
     * 부팅뿐 아니라 단위테스트/CI에서도 잡힌다. 프로덕션 배선은 위 @Bean들이다(행 하나 = 빈 하나).
     */
    public static List<TerraformOperationBinding> rows(InfraManagerFeignClient feignClient) {
        TerraformBindingCatalog catalog = new TerraformBindingCatalog();
        return List.of(
                catalog.awsServiceTfPlan(feignClient), catalog.awsServiceTfApply(feignClient),
                catalog.awsServiceTfDestroy(feignClient),
                catalog.awsBdcCommonTfPlan(feignClient), catalog.awsBdcCommonTfApply(feignClient),
                catalog.awsBdcCommonTfDestroy(feignClient),
                catalog.awsBdcServiceLevelTfPlan(feignClient), catalog.awsBdcServiceLevelTfApply(feignClient),
                catalog.awsBdcServiceLevelTfDestroy(feignClient),
                catalog.gcpServiceTfPlan(feignClient), catalog.gcpServiceTfApply(feignClient),
                catalog.gcpServiceTfDestroy(feignClient),
                catalog.gcpBdcTfPlan(feignClient), catalog.gcpBdcTfApply(feignClient),
                catalog.gcpBdcTfDestroy(feignClient),
                catalog.azureBdcTfPlan(feignClient), catalog.azureBdcTfApply(feignClient),
                catalog.azureBdcTfDestroy(feignClient),
                catalog.idcCxTfPlan(feignClient), catalog.idcCxTfApply(feignClient),
                catalog.idcCxTfDestroy(feignClient),
                catalog.idcBdpTfPlan(feignClient), catalog.idcBdpTfApply(feignClient),
                catalog.idcBdpTfDestroy(feignClient));
    }

    // ── 행 헬퍼 — 목록형/단건형 dispatch의 shape 차이는 여기서 닫는다 ──

    private static TerraformOperationBinding row(TaskOperation operation, TerraformJobType jobType,
            Function<String, List<DispatchedJob>> dispatch, Function<String, TerraformJobStatusResponse> status,
            Function<String, String> result) {
        return new CatalogTerraformBinding(operation, jobType, dispatch, status, result);
    }

    private static TerraformOperationBinding rowSingle(TaskOperation operation, TerraformJobType jobType,
            Function<String, DispatchedJob> dispatch, Function<String, TerraformJobStatusResponse> status,
            Function<String, String> result) {
        return new CatalogTerraformBinding(operation, jobType,
                target -> asList(dispatch.apply(target)), status, result);
    }

    /** 단건형 dispatch 응답을 목록으로 승격한다 — null 단건은 빈 목록이 되어 requireJobIds가 CallFailed로 닫는다. */
    private static List<DispatchedJob> asList(DispatchedJob job) {
        return job == null ? List.of() : List.of(job);
    }
}
