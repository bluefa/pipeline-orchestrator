package com.bff.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Install/Delete 파이프라인 내구 상태 머신의 Spring Boot 진입점이다(ADR-016 / PR #511 도메인 모델).
 *
 * <p>데이터베이스 행(row)이 곧 상태다. ADR-021 claim-pull 실행 모델({@code PipelineScheduler} →
 * {@code PipelineClaimer}/{@code PipelineWorker} → {@code StepRunner} → {@code StepReporter})이 due pipeline을
 * claim해 한 단계씩 밀고 나간다 — claim 트랜잭션(claim) → 외부 호출(run 단계, 트랜잭션 밖) → write-back 트랜잭션(guarded write-back). 도메인 모델은
 * {@code docs/adr/016-...}, 실행 모델은 {@code docs/adr/021-...}, 실패 처리는 {@code docs/exception-strategy.md}
 * 를 참조한다.
 */
@SpringBootApplication
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
