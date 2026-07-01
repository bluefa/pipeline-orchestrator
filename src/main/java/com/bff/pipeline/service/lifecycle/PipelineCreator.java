package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.exception.PipelinePersistenceException;
import com.bff.pipeline.exception.UnsupportedRecipeException;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
public class PipelineCreator {

    private final PipelineInserter pipelineInserter;
    private final Recipes recipes;
    private final InfraManagerClient infraManagerClient;

    public PipelineCreator(PipelineInserter pipelineInserter, Recipes recipes, InfraManagerClient infraManagerClient) {
        this.pipelineInserter = pipelineInserter;
        this.recipes = recipes;
        this.infraManagerClient = infraManagerClient;
    }

    public Pipeline create(String target, PipelineType type) {
        CloudProvider provider = infraManagerClient.cloudProvider(target);   // 트랜잭션 밖 외부 조회(§3)
        RecipeDefinition recipe = recipes.forProviderAndType(provider, type)
                .orElseThrow(() -> new UnsupportedRecipeException(provider, type));
        try {
            return pipelineInserter.insert(target, type, provider, recipe);
        } catch (DataIntegrityViolationException violation) {
            if (isActiveTargetViolation(violation)) {
                throw new PipelineAlreadyActiveException(target);
            }
            throw new PipelinePersistenceException(target, violation);
        }
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
