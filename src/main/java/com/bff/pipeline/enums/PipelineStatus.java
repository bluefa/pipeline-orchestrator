package com.bff.pipeline.enums;

/**
 * 파이프라인 생명주기 상태를 나타낸다. {@code RUNNING}만이 비종단(non-terminal) 상태이며,
 * 엔진은 이 상태의 파이프라인만 진행시킨다. 나머지 세 값은 모두 종단 상태로,
 * 한 번 진입하면 엔진이 더 이상 해당 파이프라인을 처리하지 않는다.
 */
public enum PipelineStatus {
    /** 파이프라인이 현재 실행 중인 유일한 비종단 상태. */
    RUNNING,
    /** 파이프라인의 모든 태스크가 성공적으로 완료된 종단 상태. */
    DONE,
    /** 태스크 실패 또는 오류로 인해 파이프라인이 중단된 종단 상태. */
    FAILED,
    /** 외부 요청에 의해 파이프라인이 취소된 종단 상태. */
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
