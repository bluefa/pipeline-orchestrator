package com.bff.pipeline.client.terraform;

import com.bff.pipeline.dto.DispatchedJob;
import com.bff.pipeline.dto.TerraformJobStatusResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import java.util.function.Function;

/**
 * 카탈로그 행 하나를 실행하는 제네릭 {@link TerraformOperationBinding}이다. 실 스펙에서 operation별로 다른 것은
 * "어느 Feign 메서드를 부르나"뿐이고 응답 shape은 공통이므로(설계 §1 관찰 1·2), 변환 로직은 여기 한 곳에 두고
 * operation별 차이는 {@code TerraformBindingCatalog}가 넘겨주는 메서드 참조 3개(dispatch/status/result)로 닫는다.
 * {@code jobType}은 폴 정규화의 기대 성공 상태({@code COMPLETED}/{@code DESTROYED})를 결정한다.
 */
public final class CatalogTerraformBinding implements TerraformOperationBinding {

    private final TaskOperation operation;
    private final TerraformJobType jobType;
    private final Function<String, List<DispatchedJob>> dispatch;
    private final Function<String, TerraformJobStatusResponse> status;
    private final Function<String, String> result;

    public CatalogTerraformBinding(TaskOperation operation, TerraformJobType jobType,
            Function<String, List<DispatchedJob>> dispatch, Function<String, TerraformJobStatusResponse> status,
            Function<String, String> result) {
        this.operation = operation;
        this.jobType = jobType;
        this.dispatch = dispatch;
        this.status = status;
        this.result = result;
    }

    @Override
    public TaskOperation operation() {
        return operation;
    }

    @Override
    public List<String> dispatchJobIds(String target) {
        List<DispatchedJob> jobs = dispatch.apply(target);
        List<String> jobIds = jobs == null ? null
                : jobs.stream().map(job -> job == null || job.id() == null ? null : String.valueOf(job.id())).toList();
        return TerraformOperationBinding.requireJobIds(jobIds);
    }

    @Override
    public TerraformPoll poll(String jobId) {
        return TerraformOperationBinding.toPoll(status.apply(jobId), jobType, jobId);
    }

    @Override
    public String result(String jobId) {
        return TerraformOperationBinding.requireResult(result.apply(jobId), jobId);
    }
}
