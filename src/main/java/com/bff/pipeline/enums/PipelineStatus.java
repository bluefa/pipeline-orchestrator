package com.bff.pipeline.enums;

/**
 * 파이프라인 생명주기 상태다. 비종단(non-terminal) 상태는 {@code PENDING}과 {@code RUNNING} 둘이고, 엔진은 이
 * 두 상태의 파이프라인만 claim해 진행시킨다. 나머지 세 값은 모두 종단 상태라, 한 번 들어가면 엔진이 더는
 * 건드리지 않는다. {@code PENDING}은 시작 지연(LIN-17) 대기 창의 상태이며, 지연 경과 후 첫 claim 트랜잭션에서
 * {@code RUNNING}으로 전이한다(LIN-30).
 */
public enum PipelineStatus {
    /** 시작 지연 대기 상태. 아직 첫 Task가 dispatch되지 않았고, 첫 claim 시 RUNNING으로 전이한다. 비종단. */
    PENDING,
    /** 파이프라인이 실행 중인 비종단 상태. */
    RUNNING,
    /** 모든 태스크가 성공으로 끝난 종단 상태. */
    DONE,
    /** 태스크 실패나 오류로 파이프라인이 멈춘 종단 상태. */
    FAILED,
    /** 외부 요청으로 파이프라인이 취소된 종단 상태. */
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
