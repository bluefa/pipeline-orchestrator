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
 * 행은 <b>리터럴 인자 없는 메서드 참조만</b> 갖는다 — {@code jobType} 같은 operation-결정 상수는
 * {@link InfraManagerFeignClient}의 default 메서드 안에 닫혀 있다. 공통 변환(단건→목록 승격, job id 방어,
 * 폴 정규화)은 {@link CatalogTerraformBinding} 하나가 소유한다.
 *
 * <p>새 operation 추가 = TaskOperation 값 + Feign 메서드(2~3개) + 여기 행 하나. 행을 빠뜨리면
 * {@code InfraManagerOperationRegistry}가 부팅/CI에서 실패시킨다. operation별 방어 로직이 필요해지면 그 행만
 * 별도 {@link TerraformOperationBinding} 구현 클래스로 꺼내면 된다(같은 인터페이스라 공존).
 */
@Configuration
public class TerraformBindingCatalog {

    // ── AWS 서비스 ──

    @Bean
    TerraformOperationBinding awsServiceTfPlan(InfraManagerFeignClient f) {
        return row(AWS_SERVICE_TF_PLAN, TerraformJobType.PLAN,
                f::awsServicePlan, f::awsServicePlanJobStatus, f::awsServicePlanJobResult);
    }

    @Bean
    TerraformOperationBinding awsServiceTfApply(InfraManagerFeignClient f) {
        return row(AWS_SERVICE_TF_APPLY, TerraformJobType.APPLY,
                f::awsServiceApply, f::awsServiceApplyJobStatus, f::awsServiceApplyJobResult);
    }

    @Bean
    TerraformOperationBinding awsServiceTfDestroy(InfraManagerFeignClient f) {
        return row(AWS_SERVICE_TF_DESTROY, TerraformJobType.DESTROY,
                f::awsServiceDestroy, f::awsServiceDestroyJobStatus, f::awsServiceDestroyJobResult);
    }

    // ── AWS BDC Service Level Common ──

    @Bean
    TerraformOperationBinding awsBdcCommonTfPlan(InfraManagerFeignClient f) {
        return rowSingle(AWS_BDC_COMMON_TF_PLAN, TerraformJobType.PLAN,
                f::awsBdcCommonPlan, f::awsBdcCommonPlanJobStatus, f::awsBdcCommonPlanJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcCommonTfApply(InfraManagerFeignClient f) {
        return rowSingle(AWS_BDC_COMMON_TF_APPLY, TerraformJobType.APPLY,
                f::awsBdcCommonApply, f::awsBdcCommonApplyJobStatus, f::awsBdcCommonApplyJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcCommonTfDestroy(InfraManagerFeignClient f) {
        return rowSingle(AWS_BDC_COMMON_TF_DESTROY, TerraformJobType.DESTROY,
                f::awsBdcCommonDestroy, f::awsBdcCommonDestroyJobStatus, f::awsBdcCommonDestroyJobResult);
    }

    // ── AWS BDC Service Level ──

    @Bean
    TerraformOperationBinding awsBdcServiceLevelTfPlan(InfraManagerFeignClient f) {
        return rowSingle(AWS_BDC_SERVICE_LEVEL_TF_PLAN, TerraformJobType.PLAN,
                f::awsBdcServiceLevelPlan, f::awsBdcServiceLevelPlanJobStatus, f::awsBdcServiceLevelPlanJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcServiceLevelTfApply(InfraManagerFeignClient f) {
        return rowSingle(AWS_BDC_SERVICE_LEVEL_TF_APPLY, TerraformJobType.APPLY,
                f::awsBdcServiceLevelApply, f::awsBdcServiceLevelApplyJobStatus, f::awsBdcServiceLevelApplyJobResult);
    }

    @Bean
    TerraformOperationBinding awsBdcServiceLevelTfDestroy(InfraManagerFeignClient f) {
        return rowSingle(AWS_BDC_SERVICE_LEVEL_TF_DESTROY, TerraformJobType.DESTROY,
                f::awsBdcServiceLevelDestroy, f::awsBdcServiceLevelDestroyJobStatus,
                f::awsBdcServiceLevelDestroyJobResult);
    }

    // ── GCP 서비스 ──

    @Bean
    TerraformOperationBinding gcpServiceTfPlan(InfraManagerFeignClient f) {
        return row(GCP_SERVICE_TF_PLAN, TerraformJobType.PLAN,
                f::gcpServicePlan, f::gcpServicePlanJobStatus, f::gcpServicePlanJobResult);
    }

    @Bean
    TerraformOperationBinding gcpServiceTfApply(InfraManagerFeignClient f) {
        return row(GCP_SERVICE_TF_APPLY, TerraformJobType.APPLY,
                f::gcpServiceApply, f::gcpServiceApplyJobStatus, f::gcpServiceApplyJobResult);
    }

    @Bean
    TerraformOperationBinding gcpServiceTfDestroy(InfraManagerFeignClient f) {
        return row(GCP_SERVICE_TF_DESTROY, TerraformJobType.DESTROY,
                f::gcpServiceDestroy, f::gcpServiceDestroyJobStatus, f::gcpServiceDestroyJobResult);
    }

    // ── GCP BDC ──

    @Bean
    TerraformOperationBinding gcpBdcTfPlan(InfraManagerFeignClient f) {
        return row(GCP_BDC_TF_PLAN, TerraformJobType.PLAN,
                f::gcpBdcPlan, f::gcpBdcPlanJobStatus, f::gcpBdcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding gcpBdcTfApply(InfraManagerFeignClient f) {
        return row(GCP_BDC_TF_APPLY, TerraformJobType.APPLY,
                f::gcpBdcApply, f::gcpBdcApplyJobStatus, f::gcpBdcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding gcpBdcTfDestroy(InfraManagerFeignClient f) {
        return row(GCP_BDC_TF_DESTROY, TerraformJobType.DESTROY,
                f::gcpBdcDestroy, f::gcpBdcDestroyJobStatus, f::gcpBdcDestroyJobResult);
    }

    // ── Azure BDC ──

    @Bean
    TerraformOperationBinding azureBdcTfPlan(InfraManagerFeignClient f) {
        return row(AZURE_BDC_TF_PLAN, TerraformJobType.PLAN,
                f::azureBdcPlan, f::azureBdcPlanJobStatus, f::azureBdcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding azureBdcTfApply(InfraManagerFeignClient f) {
        return row(AZURE_BDC_TF_APPLY, TerraformJobType.APPLY,
                f::azureBdcApply, f::azureBdcApplyJobStatus, f::azureBdcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding azureBdcTfDestroy(InfraManagerFeignClient f) {
        return row(AZURE_BDC_TF_DESTROY, TerraformJobType.DESTROY,
                f::azureBdcDestroy, f::azureBdcDestroyJobStatus, f::azureBdcDestroyJobResult);
    }

    // ── IDC CX / BDP (status·result 경로는 CX/BDP 공용 — 스펙 그대로) ──

    @Bean
    TerraformOperationBinding idcCxTfPlan(InfraManagerFeignClient f) {
        return rowSingle(IDC_CX_TF_PLAN, TerraformJobType.PLAN,
                f::idcCxPlan, f::idcPlanJobStatus, f::idcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding idcCxTfApply(InfraManagerFeignClient f) {
        return rowSingle(IDC_CX_TF_APPLY, TerraformJobType.APPLY,
                f::idcCxApply, f::idcApplyJobStatus, f::idcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding idcCxTfDestroy(InfraManagerFeignClient f) {
        return rowSingle(IDC_CX_TF_DESTROY, TerraformJobType.DESTROY,
                f::idcCxDestroy, f::idcDestroyJobStatus, f::idcDestroyJobResult);
    }

    @Bean
    TerraformOperationBinding idcBdpTfPlan(InfraManagerFeignClient f) {
        return rowSingle(IDC_BDP_TF_PLAN, TerraformJobType.PLAN,
                f::idcBdpPlan, f::idcPlanJobStatus, f::idcPlanJobResult);
    }

    @Bean
    TerraformOperationBinding idcBdpTfApply(InfraManagerFeignClient f) {
        return rowSingle(IDC_BDP_TF_APPLY, TerraformJobType.APPLY,
                f::idcBdpApply, f::idcApplyJobStatus, f::idcApplyJobResult);
    }

    @Bean
    TerraformOperationBinding idcBdpTfDestroy(InfraManagerFeignClient f) {
        return rowSingle(IDC_BDP_TF_DESTROY, TerraformJobType.DESTROY,
                f::idcBdpDestroy, f::idcDestroyJobStatus, f::idcDestroyJobResult);
    }

    /**
     * 테스트용 — 카탈로그 전 행을 한 번에 만든다. registry 생성자의 완전성 검증과 결합하면 "행을 여기 빠뜨림"이
     * 부팅뿐 아니라 단위테스트/CI에서도 잡힌다. 프로덕션 배선은 위 @Bean들이다(행 하나 = 빈 하나).
     */
    public static List<TerraformOperationBinding> rows(InfraManagerFeignClient f) {
        TerraformBindingCatalog catalog = new TerraformBindingCatalog();
        return List.of(
                catalog.awsServiceTfPlan(f), catalog.awsServiceTfApply(f), catalog.awsServiceTfDestroy(f),
                catalog.awsBdcCommonTfPlan(f), catalog.awsBdcCommonTfApply(f), catalog.awsBdcCommonTfDestroy(f),
                catalog.awsBdcServiceLevelTfPlan(f), catalog.awsBdcServiceLevelTfApply(f),
                catalog.awsBdcServiceLevelTfDestroy(f),
                catalog.gcpServiceTfPlan(f), catalog.gcpServiceTfApply(f), catalog.gcpServiceTfDestroy(f),
                catalog.gcpBdcTfPlan(f), catalog.gcpBdcTfApply(f), catalog.gcpBdcTfDestroy(f),
                catalog.azureBdcTfPlan(f), catalog.azureBdcTfApply(f), catalog.azureBdcTfDestroy(f),
                catalog.idcCxTfPlan(f), catalog.idcCxTfApply(f), catalog.idcCxTfDestroy(f),
                catalog.idcBdpTfPlan(f), catalog.idcBdpTfApply(f), catalog.idcBdpTfDestroy(f));
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
