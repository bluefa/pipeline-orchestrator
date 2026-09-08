package com.bff.pipeline.repository;

import com.bff.pipeline.entity.TestConnectionResult;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 외부 실행별 마지막 진단 한 건을 읽는다. 본문이 필요한 전용 상세 경로와 guarded 기록 경로에서만 사용한다. */
public interface TestConnectionResultRepository extends JpaRepository<TestConnectionResult, Long> {
    Optional<TestConnectionResult> findByExternalExecutionId(Long externalExecutionId);
}
