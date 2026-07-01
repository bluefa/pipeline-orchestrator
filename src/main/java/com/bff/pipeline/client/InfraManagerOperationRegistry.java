package com.bff.pipeline.client;

import com.bff.pipeline.client.condition.ConditionOperationBinding;
import com.bff.pipeline.client.terraform.TerraformOperationBinding;
import com.bff.pipeline.enums.TaskOperation;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * operation → InfraManager API 바인딩 레지스트리다({@link TaskTypeRegistry}와 같은 self-registering + 부팅 검증 철학).
 * 자기등록된 {@link TerraformOperationBinding}/{@link ConditionOperationBinding} 빈들을 operation으로 색인하고, 생성
 * (부팅) 시점에 완전성을 검증한다:
 *
 * <ul>
 *   <li>모든 TERRAFORM_JOB operation이 정확히 하나의 {@code TerraformOperationBinding}을 갖는다.</li>
 *   <li>모든 CONDITION_CHECK operation이 정확히 하나의 {@code ConditionOperationBinding}을 갖는다.</li>
 *   <li>바인딩이 자기 종류와 다른 mechanism의 operation을 광고하면 실패한다.</li>
 *   <li>한 operation에 바인딩이 둘 이상이면 실패한다.</li>
 * </ul>
 *
 * <p>덕분에 새 operation 추가 시 바인딩을 빠뜨리면 런타임이 아니라 <b>부팅/CI</b>에서 걸린다(바인딩 누락은 외부 호출
 * 실패가 아니라 설정/프로그래머 오류이므로 fail-fast가 맞다 — {@code docs/exception-strategy.md}).
 */
@Component
public class InfraManagerOperationRegistry {

    private final Map<TaskOperation, TerraformOperationBinding> terraform;
    private final Map<TaskOperation, ConditionOperationBinding> condition;

    public InfraManagerOperationRegistry(List<TerraformOperationBinding> terraformBindings,
            List<ConditionOperationBinding> conditionBindings) {
        this.terraform = index(terraformBindings, TerraformOperationBinding::operation,
                TaskOperation.Mechanism.TERRAFORM_JOB, "TerraformOperationBinding");
        this.condition = index(conditionBindings, ConditionOperationBinding::operation,
                TaskOperation.Mechanism.CONDITION_CHECK, "ConditionOperationBinding");
        verifyEveryOperationBound();
    }

    public TerraformOperationBinding terraform(TaskOperation operation) {
        TerraformOperationBinding binding = terraform.get(operation);
        if (binding == null) {
            throw new IllegalStateException("no TerraformOperationBinding for operation " + operation);
        }
        return binding;
    }

    public ConditionOperationBinding condition(TaskOperation operation) {
        ConditionOperationBinding binding = condition.get(operation);
        if (binding == null) {
            throw new IllegalStateException("no ConditionOperationBinding for operation " + operation);
        }
        return binding;
    }

    private static <B> Map<TaskOperation, B> index(List<B> bindings, Function<B, TaskOperation> key,
            String expectedMechanism, String kind) {
        Map<TaskOperation, B> byOperation = new EnumMap<>(TaskOperation.class);
        for (B binding : bindings) {
            TaskOperation operation = key.apply(binding);
            if (operation == null) {
                throw new IllegalStateException(kind + " " + binding.getClass().getName() + " has a null operation()");
            }
            if (!expectedMechanism.equals(operation.mechanism())) {
                throw new IllegalStateException(kind + " " + binding.getClass().getName() + " advertises operation "
                        + operation + " whose mechanism is '" + operation.mechanism() + "', not '" + expectedMechanism + "'");
            }
            B clash = byOperation.putIfAbsent(operation, binding);
            if (clash != null) {
                throw new IllegalStateException("Two " + kind + " claim operation " + operation + ": "
                        + clash.getClass().getName() + " and " + binding.getClass().getName());
            }
        }
        return byOperation;
    }

    private void verifyEveryOperationBound() {
        for (TaskOperation operation : TaskOperation.values()) {
            String mechanism = operation.mechanism();
            if (TaskOperation.Mechanism.TERRAFORM_JOB.equals(mechanism) && !terraform.containsKey(operation)) {
                throw new IllegalStateException("TaskOperation " + operation
                        + " (TERRAFORM_JOB) has no TerraformOperationBinding");
            }
            if (TaskOperation.Mechanism.CONDITION_CHECK.equals(mechanism) && !condition.containsKey(operation)) {
                throw new IllegalStateException("TaskOperation " + operation
                        + " (CONDITION_CHECK) has no ConditionOperationBinding");
            }
        }
    }
}
