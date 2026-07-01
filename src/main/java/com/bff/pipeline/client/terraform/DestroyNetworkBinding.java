package com.bff.pipeline.client.terraform;

import com.bff.pipeline.client.InfraManagerFeignClient;

import com.bff.pipeline.dto.DestroyJobStatusResponse;
import com.bff.pipeline.dto.DestroyNetworkResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import org.springframework.stereotype.Component;

/** DESTROY_NETWORK operation의 InfraManager API 바인딩. */
@Component
public class DestroyNetworkBinding implements TerraformOperationBinding {

    private final InfraManagerFeignClient feign;

    public DestroyNetworkBinding(InfraManagerFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public TaskOperation operation() {
        return TaskOperation.DESTROY_NETWORK;
    }

    @Override
    public List<String> dispatchJobIds(String target) {
        DestroyNetworkResponse response = feign.destroyNetwork(target);
        return TerraformOperationBinding.requireJobIds(response == null ? null : response.jobIds());
    }

    @Override
    public TerraformPoll poll(String jobId) {
        DestroyJobStatusResponse status = feign.destroyJobStatus(jobId);
        return TerraformOperationBinding.toPoll(status == null ? null : status.finished(),
                status == null ? null : status.succeeded(), jobId);
    }
}
