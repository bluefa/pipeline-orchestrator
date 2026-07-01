package com.bff.pipeline.service.lifecycle;
import com.bff.pipeline.service.task.terraform.TerraformTask;
import com.bff.pipeline.service.task.ConditionCheckTask;

import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.Recipe;
import com.bff.pipeline.model.RecipeStep;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link PipelineType}별 코드 기본 recipe(순서가 정해진 task 체인)를 제공한다 — ADR-016 §2가 정의한
 * "(타입, 프로바이더)별 코드 기본값"이다. 새 파이프라인 형태는 스키마를 바꾸지 않고 이 클래스에 항목으로 추가하면 된다.
 * {@code forType}은 {@link PipelineType}을 완전 열거(exhaustive)하므로, 새 타입이 생기면 런타임에 조용히 빠지는 게 아니라
 * 컴파일 오류로 잡힌다. INSTALL은 네트워크를 apply한 뒤 준비 완료를 기다리고, DELETE는 네트워크를 destroy한다.
 * 각 step은 task type 이름(taskName)과 operation을 짝지어 준다 — {@code terraformStep}은 {@link TerraformTask}에,
 * {@code conditionCheckStep}은 {@link ConditionCheckTask}에 대응한다.
 */
@Component
public class Recipes {

    private static final Recipe INSTALL_RECIPE = new Recipe(List.of(
            terraformStep(TaskOperation.APPLY_NETWORK),
            conditionCheckStep(TaskOperation.NETWORK_READY)));

    private static final Recipe DELETE_RECIPE = new Recipe(List.of(
            terraformStep(TaskOperation.DESTROY_NETWORK)));

    public Recipe forType(PipelineType type) {
        return switch (type) {
            case INSTALL -> INSTALL_RECIPE;
            case DELETE -> DELETE_RECIPE;
        };
    }

    private static RecipeStep terraformStep(TaskOperation operation) {
        return new RecipeStep(TerraformTask.NAME, operation);
    }

    private static RecipeStep conditionCheckStep(TaskOperation operation) {
        return new RecipeStep(ConditionCheckTask.NAME, operation);
    }
}
