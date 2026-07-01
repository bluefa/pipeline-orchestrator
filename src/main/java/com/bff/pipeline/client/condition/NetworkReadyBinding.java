package com.bff.pipeline.client.condition;

import com.bff.pipeline.client.InfraManagerFeignClient;

import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.dto.NetworkReadyResponse;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** NETWORK_READY operation의 InfraManager API 바인딩. */
@Component
public class NetworkReadyBinding implements ConditionOperationBinding {

    private final InfraManagerFeignClient feign;
    private final ObjectMapper objectMapper;

    public NetworkReadyBinding(InfraManagerFeignClient feign, ObjectMapper objectMapper) {
        this.feign = feign;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskOperation operation() {
        return TaskOperation.NETWORK_READY;
    }

    @Override
    public ConditionPoll check(String target) {
        NetworkReadyResponse response = feign.networkReady(target);
        return ConditionOperationBinding.poll(response == null ? null : response.met(), response, objectMapper,
                TaskOperation.NETWORK_READY);
    }
}
