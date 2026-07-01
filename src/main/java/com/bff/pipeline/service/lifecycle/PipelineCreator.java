package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.exception.PipelineCreationConflictException;
import com.bff.pipeline.repository.PipelineRepository;
import java.util.Locale;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 멱등적 파이프라인 생성을 구현한다(ADR-016 §4). 대상의 실행을 새로 시작하되, 이미 활성 실행이 있으면
 * 오류를 던지지 않고 그 실행을 그대로 돌려준다. 덕분에 트리거 계약이 지켜진다 — 어떤 타입이든 중복 생성 요청은
 * 진행 중인 실행을 반환하는 안전한 no-op이 된다.
 *
 * <p>{@code @Transactional}을 일부러 붙이지 않는다. inserter의 트랜잭션이 유니크 제약 위반으로 롤백되고 나면, 복구 조회는
 * 이미 커밋된 기존 실행을 읽어야 하므로 별도의 새 트랜잭션에서 돌아야 하기 때문이다. {@link DataIntegrityViolationException}은
 * 여기서 도메인 응답으로 번역하는 유일한 외부/인프라 실패다({@code docs/exception-strategy.md} 참조).
 *
 * <p>"실행이 이미 존재한다"를 뜻하는 것은 오직 active-target 유니크 위반뿐이다(위반 원인 체인의 제약 이름으로 판별한다).
 * 나머지 무결성 위반은 진짜 버그이므로 그대로 밖으로 내보낸다. insert는 정해진 횟수만큼 재시도한다. 실패한 insert와 복구
 * 조회 사이에 활성 실행이 끝나 대상이 풀릴 수 있는데, 그 순간 조회는 빈 결과를 내놓기 때문이다 — 이때 올바른 대응은 낡아버린
 * 위반을 노출하는 게 아니라 insert를 다시 시도하는 것이다. 이 틈은 동시 종료 하나당 한 번만 열리므로 두세 번이면 충분하고,
 * 그래도 수렴하지 못하면 {@link PipelineCreationConflictException}(409)로 알린다.
 */
@Service
public class PipelineCreator {

    private static final int DUPLICATE_CREATE_RETRY_LIMIT = 3;

    private final PipelineInserter pipelineInserter;
    private final PipelineRepository pipelineRepository;

    public PipelineCreator(PipelineInserter pipelineInserter, PipelineRepository pipelineRepository) {
        this.pipelineInserter = pipelineInserter;
        this.pipelineRepository = pipelineRepository;
    }

    public Pipeline create(String target, PipelineType type) {
        for (int attempt = 1; attempt <= DUPLICATE_CREATE_RETRY_LIMIT; attempt++) {
            Optional<Pipeline> pipeline = insertOrFindActive(target, type);
            if (pipeline.isPresent()) {
                return pipeline.get();
            }
            // empty = active-target 위반이었지만 그 사이 대상이 종료돼 풀림(경합) → 재시도한다.
        }
        throw new PipelineCreationConflictException(target, DUPLICATE_CREATE_RETRY_LIMIT);
    }

    /**
     * insert를 한 번 시도한다. 성공하면 새 pipeline을, active-target 위반이면 기존 활성 실행을 담아 돌려준다.
     * 위반이 났지만 그 사이 대상이 종료돼 활성 실행이 사라졌으면(경합) empty를 돌려 재시도를 요청한다.
     * active-target이 아닌 무결성 위반은 진짜 버그이므로 그대로 전파한다.
     */
    private Optional<Pipeline> insertOrFindActive(String target, PipelineType type) {
        try {
            return Optional.of(pipelineInserter.insert(target, type));
        } catch (DataIntegrityViolationException violation) {
            if (!isActiveTargetViolation(violation)) {
                throw violation;
            }
            return pipelineRepository.findByActiveTarget(target);
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
