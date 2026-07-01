package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.condition.ConditionOperationBinding;
import com.bff.pipeline.client.terraform.TerraformOperationBinding;
import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * operation → InfraManager 바인딩 완전성의 부팅 검증을 다룬다: 모든 operation이 자기 mechanism의 바인딩을 정확히
 * 하나 가져야 하고, 누락·중복·mechanism 불일치는 부팅(생성)에서 실패한다.
 */
class InfraManagerOperationRegistryTest {

    @Test
    void indexesEveryOperationWhenAllBindingsArePresent() {
        InfraManagerOperationRegistry registry = new InfraManagerOperationRegistry(
                List.of(terraform(TaskOperation.APPLY_NETWORK), terraform(TaskOperation.DESTROY_NETWORK)),
                List.of(condition(TaskOperation.NETWORK_READY)));

        assertThat(registry.terraform(TaskOperation.APPLY_NETWORK).operation()).isEqualTo(TaskOperation.APPLY_NETWORK);
        assertThat(registry.condition(TaskOperation.NETWORK_READY).operation()).isEqualTo(TaskOperation.NETWORK_READY);
    }

    @Test
    void failsBootWhenATerraformOperationHasNoBinding() {
        assertThatThrownBy(() -> new InfraManagerOperationRegistry(
                List.of(terraform(TaskOperation.APPLY_NETWORK)),   // DESTROY_NETWORK 누락
                List.of(condition(TaskOperation.NETWORK_READY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DESTROY_NETWORK");
    }

    @Test
    void failsBootWhenAConditionOperationHasNoBinding() {
        assertThatThrownBy(() -> new InfraManagerOperationRegistry(
                List.of(terraform(TaskOperation.APPLY_NETWORK), terraform(TaskOperation.DESTROY_NETWORK)),
                List.of()))   // NETWORK_READY 누락
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NETWORK_READY");
    }

    @Test
    void rejectsTwoBindingsClaimingTheSameOperation() {
        assertThatThrownBy(() -> new InfraManagerOperationRegistry(
                List.of(terraform(TaskOperation.APPLY_NETWORK), terraform(TaskOperation.APPLY_NETWORK),
                        terraform(TaskOperation.DESTROY_NETWORK)),
                List.of(condition(TaskOperation.NETWORK_READY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim operation");
    }

    @Test
    void rejectsABindingThatAdvertisesAnOperationOfTheWrongMechanism() {
        // TerraformOperationBinding이 CONDITION_CHECK operation(NETWORK_READY)을 광고 → mechanism 불일치.
        assertThatThrownBy(() -> new InfraManagerOperationRegistry(
                List.of(terraform(TaskOperation.NETWORK_READY)),
                List.of(condition(TaskOperation.NETWORK_READY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mechanism");
    }

    private TerraformOperationBinding terraform(TaskOperation operation) {
        return new TerraformOperationBinding() {
            @Override public TaskOperation operation() { return operation; }
            @Override public List<String> dispatchJobIds(String target) { return List.of("job"); }
            @Override public TerraformPoll poll(String jobId) { return TerraformPoll.running(); }
        };
    }

    private ConditionOperationBinding condition(TaskOperation operation) {
        return new ConditionOperationBinding() {
            @Override public TaskOperation operation() { return operation; }
            @Override public ConditionPoll check(String target) { return new ConditionPoll(false, "{}"); }
        };
    }
}
