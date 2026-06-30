package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.PipelineStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Pipeline} 행(row)의 영속성 계층이다. {@code findByActiveTarget}은 해당 target의 현재 활성 실행(run)을
 * 반환한다(없으면 빈 값): 불변식에 따라 실행이 비종료(non-terminal) 상태인 동안에만 정확히
 * {@code active_target == target}이 성립하며(종료 시 NULL로 초기화됨), {@code uq_pipeline_active_target}
 * 유일 인덱스를 활용하여 최대 하나의 행을 반환한다. 이 메서드는 중복 생성(duplicate-create) 유일 위반(unique violation)
 * 이후 기존 실행을 복구하는 데 사용된다. {@code finishIfRunning}은 pipeline을 종료 상태(terminal status)로 수렴(converge)시키고
 * {@code active_target}을 초기화하여 해당 target이 재사용 가능하게 한다. 이 메서드는 RUNNING 상태의 pipeline에
 * 대해서만 이동하도록 가드(guarded)되어 있으므로, 동시에 진행된 cancel이 이미 CANCELLED로 이동시킨 pipeline을
 * converge가 덮어쓸 수 없다(결과 행이 0인 경우가 해당 no-op이다). 또한 flush+clear를 수행하므로 호출자는
 * 낡은 1차 캐시(first-level-cache) 엔티티가 아닌 커밋된 상태를 다시 읽는다.
 */
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    Optional<Pipeline> findByActiveTarget(String activeTarget);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Pipeline p set p.status = :to, p.activeTarget = null, p.lastActivityAt = :now "
            + "where p.id = :id and p.status = com.bff.pipeline.enums.PipelineStatus.RUNNING")
    int finishIfRunning(@Param("id") Long id, @Param("to") PipelineStatus to, @Param("now") Instant now);
}
