package com.bff.pipeline.client.condition;

import com.bff.pipeline.client.InfraManagerFeignClient;

import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.dto.NetworkReadyResponse;
import com.bff.pipeline.enums.TaskOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** NETWORK_READY operation의 InfraManager API 바인딩. */
@Component
@RequiredArgsConstructor
public class NetworkReadyBinding implements ConditionOperationBinding {

    private final InfraManagerFeignClient feign;

    @Override
    public TaskOperation operation() {
        return TaskOperation.NETWORK_READY;
    }

    @Override
    public ConditionPoll check(String target) {
        NetworkReadyResponse response = feign.networkReady(target);
        return ConditionOperationBinding.poll(response == null ? null : response.met(),
                response == null ? null : response.raw(), TaskOperation.NETWORK_READY);
    }
}
