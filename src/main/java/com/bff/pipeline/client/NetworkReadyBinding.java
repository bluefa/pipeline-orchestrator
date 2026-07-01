package com.bff.pipeline.client;

import com.bff.pipeline.dto.NetworkReadyResponse;
import com.bff.pipeline.enums.TaskOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** NETWORK_READY operation의 InfraManager API 바인딩. */
@Component
@ConditionalOnProperty(prefix = "infra-manager", name = "base-url")
public class NetworkReadyBinding implements ConditionOperationBinding {

    private final InfraManagerFeignClient feign;

    public NetworkReadyBinding(InfraManagerFeignClient feign) {
        this.feign = feign;
    }

    @Override
    public TaskOperation operation() {
        return TaskOperation.NETWORK_READY;
    }

    @Override
    public boolean check(String target) {
        NetworkReadyResponse response = feign.networkReady(target);
        return ConditionOperationBinding.requireMet(response == null ? null : response.met(), TaskOperation.NETWORK_READY);
    }
}
