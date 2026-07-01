package com.bff.pipeline.service.lifecycle;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.model.Recipe;
import com.bff.pipeline.model.RecipeStep;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link PipelineType}별 코드 기본 recipe(순서가 정해진 task 체인)를 제공한다 — ADR-016 §2가 정의한
 * "(타입, 프로바이더)별 코드 기본값"이다. 새 파이프라인 형태는 스키마를 바꾸지 않고 이 클래스에 항목으로 추가하면 된다.
 * {@code forType}은 {@link PipelineType}을 완전 열거(exhaustive)하므로, 새 타입이 생기면 런타임에 조용히 빠지는 게 아니라
 * 컴파일 오류로 잡힌다. INSTALL은 네트워크를 apply한 뒤 준비 완료를 기다리고, DELETE는 네트워크를 destroy한다.
 * 각 step은 명명된 {@link TaskDefinition} 하나를 가리킨다(설계: {@code docs/task-catalog-extension-plan.md}).
 */
@Component
public class Recipes {

    private static final Recipe INSTALL_RECIPE = new Recipe(List.of(
            new RecipeStep(TaskDefinition.APPLY_NETWORK_V1),
            new RecipeStep(TaskDefinition.NETWORK_READY_V1)));

    private static final Recipe DELETE_RECIPE = new Recipe(List.of(
            new RecipeStep(TaskDefinition.DESTROY_NETWORK_V1)));

    public Recipe forType(PipelineType type) {
        return switch (type) {
            case INSTALL -> INSTALL_RECIPE;
            case DELETE -> DELETE_RECIPE;
        };
    }
}
