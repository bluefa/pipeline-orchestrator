package com.bff.pipeline.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.terraform.TerraformJobType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * TaskDefinition 카탈로그의 완전성과 실행 계약 metadata의 정합을 못박는다. 바인딩 카탈로그(TerraformBindingCatalog)는
 * 부팅 검증이 지키지만, 정의 카탈로그는 컴파일·부팅 어느 쪽도 강제하지 못하므로 여기서 회귀로 지킨다 —
 * operation을 추가하고 정의를 빠뜨리면 즉시 실패한다.
 */
class TaskDefinitionTest {

    @Test
    void everyOperationHasADefinition() {
        Set<TaskOperation> covered = Arrays.stream(TaskDefinition.values())
                .map(TaskDefinition::operation)
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrder(TaskOperation.values());
    }

    @Test
    void terraformDefinitionsCarryAllThreeApisAndPolicies() {
        for (TaskDefinition definition : terraformDefinitions()) {
            TaskExecutionSpec spec = definition.spec();
            assertThat(spec.dispatchApi()).as("%s dispatch", definition).matches("^(GET|POST|DELETE) /infra/.*");
            assertThat(spec.statusApi()).as("%s status", definition).matches("^GET /infra/.*\\{terraformJobId}.*");
            assertThat(spec.resultApi()).as("%s result", definition).matches("^GET /infra/.*result.*");
            assertThat(spec.successPolicy()).as("%s policy", definition).isNotBlank();
            assertThat(spec.resultStorage()).as("%s storage", definition).contains("terraform_result");
        }
    }

    /** 성공 terminal 상태는 job 타입이 결정한다(owner 확정) — 정책 텍스트가 operation의 job 타입과 어긋나면 실패한다. */
    @Test
    void successPolicyNamesTheTerminalStateOfItsJobType() {
        for (TaskDefinition definition : terraformDefinitions()) {
            TerraformJobType expectedJobType = jobTypeOf(definition.operation());
            assertThat(definition.spec().successPolicy()).as("%s", definition)
                    .contains(expectedJobType.successState());
        }
    }

    /** operation 이름의 _TF_{PLAN|APPLY|DESTROY} 접미가 job 타입을 결정한다는 명명 규약에 기댄다. */
    private static TerraformJobType jobTypeOf(TaskOperation operation) {
        String suffix = operation.name().substring(operation.name().lastIndexOf("_TF_") + "_TF_".length());
        return TerraformJobType.valueOf(suffix);
    }

    @Test
    void conditionCheckDefinitionsHaveNoDispatchAndNoResult() {
        for (TaskDefinition definition : TaskDefinition.values()) {
            if (definition.operation().consumesTerraformSlot()) {
                continue;
            }
            TaskExecutionSpec spec = definition.spec();
            assertThat(spec.dispatchApi()).as("%s dispatch", definition).isNull();
            assertThat(spec.resultApi()).as("%s result", definition).isNull();
            assertThat(spec.statusApi()).as("%s check", definition).isNotBlank();
            assertThat(spec.resultStorage()).as("%s storage", definition).contains("task_check");
        }
    }

    private static Set<TaskDefinition> terraformDefinitions() {
        return Arrays.stream(TaskDefinition.values())
                .filter(definition -> definition.operation().consumesTerraformSlot())
                .collect(Collectors.toSet());
    }
}
