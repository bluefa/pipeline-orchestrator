package com.bff.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Install/Delete 파이프라인 내구성 상태 머신의 Spring Boot 애플리케이션 진입점이다
 * (ADR-016 / PR #511 도메인 모델, ADR-021 / PR #512 실행 모델).
 *
 * <p>데이터베이스 행(row)이 곧 상태이다. {@link com.bff.pipeline.service.PipelineScheduler}가
 * 적응형 자기-재조정 스윕을 실행하여 {@link com.bff.pipeline.service.PipelineWorker#pollOnce}
 * 클레임을 워커 풀에 분산한다. 각 파이프라인은 두 트랜잭션 사이클(tx1 클레임 → 외부 호출 →
 * tx2 가드 라이트백)로 한 단계씩 진행된다(ADR-021 Decision 4). 도메인 모델은
 * {@code docs/adr/016-...}을, 실행 모델은 {@code docs/adr/021-...}을,
 * 실패 처리는 {@code docs/exception-strategy.md}를 참조한다.
 */
@SpringBootApplication
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
