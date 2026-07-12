package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.pipeline.CustomTaskRequest;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.exception.EmptyCustomRecipeException;
import com.bff.pipeline.exception.MissingPipelineTypeException;
import com.bff.pipeline.exception.MissingTargetException;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.exception.PipelinePersistenceException;
import com.bff.pipeline.exception.ProviderLookupException;
import com.bff.pipeline.exception.TaskDescriptionTooLongException;
import com.bff.pipeline.exception.TaskProviderMismatchException;
import com.bff.pipeline.exception.UnknownTaskException;
import com.bff.pipeline.exception.UnsupportedRecipeException;
import com.bff.pipeline.model.PipelinePlan;
import com.bff.pipeline.model.PipelinePlan.PlannedStep;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 파이프라인 생성(트리거)을 구현한다(ADR-016 §4). 대상의 실행을 새로 시작하되, 이미 비종료 활성 실행이 있으면
 * {@link PipelineAlreadyActiveException}(409)로 거절한다. 트리거는 web admin 페이지의 사람 콜이므로, 중복(더블클릭·
 * 타임아웃 재클릭)을 조용히 흡수하지 않고 "이미 실행 중"을 명확히 알리는 편이 낫다. target당 활성 하나라는 불변식은
 * {@code active_target} 유니크 제약이 보장하며 이 트리거 계약과 독립적이다 — 워커는 create를 부르지 않고 기존 실행을 claim한다.
 *
 * <p>{@code @Transactional}을 일부러 붙이지 않는다. inserter의 트랜잭션이 유니크 제약 위반으로 롤백돼도 이 계층은
 * 그 위반을 도메인 응답으로 번역하기만 하기 때문이다({@code docs/exception-strategy.md} 참조).
 *
 * <p>"실행이 이미 존재한다"를 뜻하는 것은 오직 active-target 유니크 위반뿐이다(위반 원인 체인의 제약 이름으로 판별한다).
 * 그 외 무결성 위반은 예상 밖 버그이므로 raw 예외가 컨트롤러까지 새지 않도록 {@link PipelinePersistenceException}(500)으로
 * 감싸 전파한다.
 */
@Service
@RequiredArgsConstructor
public class PipelineCreator {

    private final PipelineInserter pipelineInserter;
    private final RecipeCatalog recipeCatalog;
    private final InfraManagerClient infraManagerClient;

    /** 카탈로그 recipe 실행(P10). (provider, type)으로 고정 recipe를 골라 실행한다. */
    public Pipeline create(String target, PipelineType type) {
        if (type == null) {
            throw new MissingPipelineTypeException();
        }
        return insert(PipelinePlan.fromCatalog(target, resolveRecipe(target, type)), target);   // 입력 검증 + 트랜잭션 밖 외부 조회(§3)
    }

    /**
     * custom recipe 실행(LIN-18, 비영속). 요청이 준 task 순서·이름대로 실행하며 type은 {@link PipelineType#CUSTOM}으로
     * 고정된다(INSTALL/DELETE를 받지 않는다 — custom은 그 자체가 분류다). tasks가 비어 있으면 400이고, 각 task는
     * 이름 존재·provider 일치·설명 길이(≤100)를 검사해 하나라도 어기면 400이다. plan 구성은 트랜잭션 밖에서 입력 검증 +
     * provider 조회를 마치고 삽입만 inserter의 트랜잭션에 맡긴다 — 유니크 위반은 {@link #insert}가 도메인 응답으로 번역한다.
     */
    public Pipeline createCustom(String target, List<CustomTaskRequest> tasks) {
        if (target == null || target.isBlank()) {
            throw new MissingTargetException();
        }
        if (tasks == null || tasks.isEmpty()) {
            throw new EmptyCustomRecipeException();
        }
        // provider와 무관한 검증(이름 존재·설명 길이)은 외부 조회 전에 끝낸다(§3) — provider 조회가 죽어도 malformed 요청은 400을 유지한다.
        List<PlannedStep> steps = tasks.stream().map(PipelineCreator::resolveNameAndDescription).toList();
        CloudProvider provider = resolveProvider(target);   // 트랜잭션 밖 외부 조회(§3)
        steps.forEach(step -> requireProviderMatch(step.definition(), provider));
        return insert(PipelinePlan.custom(target, provider, steps), target);
    }

    /** plan 삽입 + active-target 유니크 위반의 도메인 번역. catalog/custom 경로가 공유한다. */
    private Pipeline insert(PipelinePlan plan, String target) {
        try {
            return pipelineInserter.insert(plan);
        } catch (DataIntegrityViolationException violation) {
            if (isActiveTargetViolation(violation)) {
                throw new PipelineAlreadyActiveException(target);
            }
            throw new PipelinePersistenceException(target, violation);
        }
    }

    /** provider와 무관한 pass 1: task 이름을 해석하고 설명 길이를 검사한다 — 미존재는 UNKNOWN_TASK, 초과는 DESCRIPTION_TOO_LONG(400). */
    private static PlannedStep resolveNameAndDescription(CustomTaskRequest task) {
        String name = task == null ? null : task.name();
        TaskDefinition definition = TaskDefinition.find(name)
                .orElseThrow(() -> new UnknownTaskException(name));
        String description = task == null ? null : task.description();
        if (description != null && description.length() > CustomTaskRequest.MAX_DESCRIPTION_LENGTH) {
            throw new TaskDescriptionTooLongException(name, description.length(),
                    CustomTaskRequest.MAX_DESCRIPTION_LENGTH);
        }
        return new PlannedStep(definition, description);
    }

    /** pass 2: task의 provider가 target provider와 일치하는지 검사한다(외부 조회로 얻은 provider가 있어야 가능) — 불일치는 400. */
    private static void requireProviderMatch(TaskDefinition definition, CloudProvider provider) {
        if (definition.provider() != provider) {
            throw new TaskProviderMismatchException(definition.name(), definition.provider(), provider);
        }
    }

    /**
     * 실행 전 recipe 미리보기(P9). create와 동일하게 target을 검증하고 provider를 조회해 recipe를 고르지만,
     * 아무것도 저장하지 않는 읽기 전용 경로다 — 실제 실행이 만들 task 체인을 관리자에게 미리 보여준다.
     * 실패 계약도 create와 같다(빈 target 400, provider 조회 실패 503, 미지원 recipe 400).
     */
    public RecipeDefinition preview(String target, PipelineType type) {
        return resolveRecipe(target, type);
    }

    /** target·type 검증 → provider 조회(외부, 트랜잭션 밖) → (provider, type) recipe 선택. create와 preview가 공유한다. */
    private RecipeDefinition resolveRecipe(String target, PipelineType type) {
        if (target == null || target.isBlank()) {           // 외부 조회 전에 입력을 검증한다
            throw new MissingTargetException();
        }
        if (type == PipelineType.CUSTOM) {                  // CUSTOM은 카탈로그 유형이 아니다 — provider 조회 전에 400으로 거절(§3)
            throw new UnsupportedRecipeException(type);
        }
        CloudProvider provider = resolveProvider(target);   // 트랜잭션 밖 외부 조회(§3)
        return recipeCatalog.forProviderAndType(provider, type)
                .orElseThrow(() -> new UnsupportedRecipeException(provider, type));
    }

    /**
     * cloud provider를 조회한다(외부 호출). 인프라 실패(타임아웃/호출 오류)는 비즈니스 실패가 아니므로 503으로
     * 번역한다 — raw CallTimeout/CallFailed가 catch-all로 새어 500이 되지 않게 한다(§3). CallInterrupted는 잡지 않고
     * 그대로 전파한다(fail-fast).
     */
    private CloudProvider resolveProvider(String target) {
        CloudProvider provider;
        try {
            provider = infraManagerClient.cloudProvider(target);
        } catch (CallTimeoutException | CallFailedException lookupFailure) {
            throw new ProviderLookupException(target, lookupFailure);
        }
        if (provider == null) {   // 경계 계약 위반(null 반환)은 degraded 값으로 흘리지 않고 조회 실패로 번역한다
            throw new ProviderLookupException(target, null);
        }
        return provider;
    }

    private static boolean isActiveTargetViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && namesActiveTargetConstraint(constraintViolation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesActiveTargetConstraint(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(Pipeline.ACTIVE_TARGET_CONSTRAINT);
    }
}
