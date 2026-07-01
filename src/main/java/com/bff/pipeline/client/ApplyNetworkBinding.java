package com.bff.pipeline.client;

import com.bff.pipeline.dto.ApplyJobStatusResponse;
import com.bff.pipeline.dto.ApplyNetworkResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** APPLY_NETWORK operation의 InfraManager API 바인딩. */
@Component
@ConditionalOnProperty(prefix = "infra-manager", name = "base-url")
public class ApplyNetworkBinding implements TerraformOperationBinding {

    private final InfraManagerFeignClient feign;

    public ApplyNetworkBinding(InfraManagerFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public TaskOperation operation() {
        return TaskOperation.APPLY_NETWORK;
    }

    @Override
    public List<String> dispatchJobIds(String target) {
        ApplyNetworkResponse response = feign.applyNetwork(target);
        return TerraformOperationBinding.requireJobIds(response == null ? null : response.jobIds());
    }

    @Override
    public TerraformPoll poll(String jobId) {
        ApplyJobStatusResponse status = feign.applyJobStatus(jobId);
        return TerraformOperationBinding.toPoll(status == null ? null : status.finished(),
                status == null ? null : status.succeeded(), jobId);
    }
}
