package com.bff.pipeline.service.pipeline;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.repository.PipelineRepository;
import java.util.Locale;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 멱등적 파이프라인 생성을 구현한다 (ADR-016 §4). 대상에 대한 실행을 새로 시작하거나,
 * 이미 활성 실행이 존재하면 오류를 발생시키는 대신 해당 실행을 반환한다. 이는 트리거 계약을
 * 충족한다: 어떤 타입이든 중복 생성 요청은 진행 중인 실행을 반환하는 안전한 no-op이다.
 *
 * <p>의도적으로 {@code @Transactional}을 붙이지 않는다: inserter의 트랜잭션이 유니크 제약
 * 위반으로 롤백될 때, 복구 조회는 커밋된 기존 실행을 읽기 위해 새 트랜잭션에서 실행되어야 한다.
 * {@link DataIntegrityViolationException} 캐치는 도메인 응답으로 변환하는 유일한
 * 외부/인프라 실패이다({@code docs/exception-strategy.md} 참조).
 *
 * <p>오직 active-target 유니크 위반만이 "실행이 이미 존재한다"를 의미한다(제약 이름으로 판별하며,
 * 제약 이름을 생략하는 드라이버를 위해 예외 메시지를 대체 수단으로 사용한다). 그 외 무결성 위반은
 * 진짜 버그이므로 그대로 노출된다. insert는 제한된 횟수만큼 재시도된다: 실패한 insert와
 * 복구 조회 사이에 활성 실행이 종료되어 대상이 해제될 수 있기 때문이다 — 그 구간에서는 조회
 * 결과가 비어 있으며, 올바른 처리는 이제 낡아버린 위반을 노출하는 것이 아니라 insert를
 * 재시도하는 것이다. 이 구간은 동시적 종료 하나당 한 번만 열리므로 두세 번의 시도로 충분하다.
 */
@Service
public class PipelineCreator {

    private static final int DUPLICATE_CREATE_RETRY_LIMIT = 3;

    private final PipelineInserter inserter;
    private final PipelineRepository pipelines;

    public PipelineCreator(PipelineInserter inserter, PipelineRepository pipelines) {
        this.inserter = inserter;
        this.pipelines = pipelines;
    }

    public Pipeline create(String target, PipelineType type) {
        DataIntegrityViolationException lastViolation = null;
        for (int attempt = 0; attempt < DUPLICATE_CREATE_RETRY_LIMIT; attempt++) {
            try {
                return inserter.insert(target, type);
            } catch (DataIntegrityViolationException duplicate) {
                if (!isActiveTargetViolation(duplicate)) {
                    throw duplicate;
                }
                Optional<Pipeline> active = pipelines.findByActiveTarget(target);
                if (active.isPresent()) {
                    return active.get();
                }
                lastViolation = duplicate;
            }
        }
        throw lastViolation;
    }

    private static boolean isActiveTargetViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && namesActiveTargetConstraint(constraintViolation.getConstraintName())) {
                return true;
            }
        }
        return namesActiveTargetConstraint(exception.getMessage());
    }

    private static boolean namesActiveTargetConstraint(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(Pipeline.ACTIVE_TARGET_CONSTRAINT);
    }
}
