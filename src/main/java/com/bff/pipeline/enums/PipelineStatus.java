package com.bff.pipeline.enums;

/**
 * 파이프라인 생명주기 상태다. 비종단(non-terminal) 상태는 {@code RUNNING} 하나뿐이고, 엔진은 이 상태의
 * 파이프라인만 진행시킨다. 나머지 세 값은 모두 종단 상태라, 한 번 들어가면 엔진이 더는 건드리지 않는다.
 */
public enum PipelineStatus {
    /** 파이프라인이 실행 중인 유일한 비종단 상태. */
    RUNNING,
    /** 모든 태스크가 성공으로 끝난 종단 상태. */
    DONE,
    /** 태스크 실패나 오류로 파이프라인이 멈춘 종단 상태. */
    FAILED,
    /** 외부 요청으로 파이프라인이 취소된 종단 상태. */
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
