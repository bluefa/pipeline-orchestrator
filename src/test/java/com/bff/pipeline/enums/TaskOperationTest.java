package com.bff.pipeline.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.terraform.TerraformJobType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * terraformAction() 파생을 회귀로 못박는다. 이 값은 운영 UI가 job 노드에 PLAN/APPLY/DESTROY 태그로 표시하는
 * 라벨이라, operation 이름 규약(…_TF_PLAN/…_TF_APPLY/…_TF_DESTROY)이 깨지거나 새 terraform operation이
 * 규약을 벗어나면 태그가 조용히 사라진다 — 여기서 잡는다.
 */
class TaskOperationTest {

    private static final Set<String> JOB_TYPE_NAMES = Arrays.stream(TerraformJobType.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    @Test
    void everyTerraformOperationYieldsAKnownAction() {
        for (TaskOperation operation : TaskOperation.values()) {
            if (TaskOperation.Mechanism.TERRAFORM_JOB.equals(operation.mechanism())) {
                assertThat(operation.terraformAction())
                        .as("terraform operation %s must derive an action", operation)
                        .hasValueSatisfying(action -> assertThat(JOB_TYPE_NAMES).contains(action));
            }
        }
    }

    @Test
    void nonTerraformOperationHasNoAction() {
        assertThat(TaskOperation.NETWORK_READY.terraformAction()).isEmpty();
    }
}
