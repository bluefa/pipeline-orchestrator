package com.bff.pipeline.recipe;

import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.Recipe;
import com.bff.pipeline.model.RecipeStep;
import com.bff.pipeline.service.task.type.ConditionCheckTask;
import com.bff.pipeline.service.task.type.TerraformTask;
import java.util.List;

/**
 * 각 {@link PipelineType}에 대한 코드 기본 recipe(순서가 정해진 task 체인)를 제공한다 —
 * ADR-016 §2에서 정의한 "(타입, 프로바이더)별 코드 기본값"이다.
 * 스프링 빈 수명주기가 필요 없는 순수 정의이므로 정적 유틸리티로 둔다 (주입 대신 직접 참조).
 * 새로운 파이프라인 형태는 스키마 변경이 아닌 이 클래스에 새 항목으로 추가한다.
 * {@code forType}은 {@link PipelineType}에 대해 완전 열거(exhaustive)이므로, 새 타입이 추가되면
 * 런타임 누락이 아닌 컴파일 오류가 발생한다: INSTALL은 네트워크를 적용(apply)한 후 준비 완료를
 * 기다리고, DELETE는 네트워크를 삭제(destroy)한다.
 * 각 step은 task type 이름(taskName)과 operation을 매핑한다 — {@code terraformStep}은
 * {@link TerraformTask}, {@code conditionCheckStep}은 {@link ConditionCheckTask}에 해당한다.
 */
public final class Recipes {

    private Recipes() {}

    private static final Recipe INSTALL_RECIPE = new Recipe(List.of(
            terraformStep(TaskOperation.APPLY_NETWORK),
            conditionCheckStep(TaskOperation.NETWORK_READY)));

    private static final Recipe DELETE_RECIPE = new Recipe(List.of(
            terraformStep(TaskOperation.DESTROY_NETWORK)));

    public static Recipe forType(PipelineType type) {
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
