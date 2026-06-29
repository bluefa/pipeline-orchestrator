package com.bff.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Install/Delete 파이프라인 내구성 상태 머신의 Spring Boot 애플리케이션 진입점이다(ADR-016 / PR #511 도메인 모델).
 *
 * <p>데이터베이스 행(row)이 곧 상태이다. 도메인은 {@code PipelineEngine.advance(pipelineId)}라는
 * 단일 전진 오퍼레이션을 노출하며, 이는 파이프라인 상태 머신을 한 단계 진행시킨다.
 * ADR-021 러너가 언제, 얼마나 자주, 어떤 동시성으로 이를 호출할지를 결정하며,
 * 해당 실행 모델은 이 모듈의 범위 밖이다(스케줄러나 재조정 루프는 포함하지 않는다).
 * 도메인 모델은 {@code docs/adr/016-...}을, 실패 처리는 {@code docs/exception-strategy.md}를 참조한다.
 */
@SpringBootApplication
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
