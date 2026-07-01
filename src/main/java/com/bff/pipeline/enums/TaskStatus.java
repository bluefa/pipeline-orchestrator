package com.bff.pipeline.enums;

/**
 * 태스크 생명주기를 나타낸다(ADR-016 §2).
 *
 * <pre>
 *   BLOCKED ──▶ READY ──▶ IN_PROGRESS ──▶ DONE | FAILED | CANCELLED
 * </pre>
 *
 * 태스크는 {@code BLOCKED}로 생성되고(파이프라인 첫 태스크만 예외적으로 {@code READY}로 생성된다),
 * 선행 태스크가 {@code DONE}이 되면 {@code READY}로 넘어간다. <em>현재 태스크(current task)</em>는 순번이
 * 가장 낮은 {@code READY}/{@code IN_PROGRESS} 태스크이고, 그 뒤 태스크들은 {@code BLOCKED}로 남는다.
 */
public enum TaskStatus {
    /** 선행 태스크가 아직 안 끝나 실행할 수 없는 상태. */
    BLOCKED,
    /** 선행 조건이 충족돼 실행 준비가 된 상태. */
    READY,
    /** dispatch나 poll이 진행 중인 상태. */
    IN_PROGRESS,
    /** 태스크가 성공으로 끝난 종단 상태. */
    DONE,
    /** 태스크가 실패로 끝난 종단 상태. */
    FAILED,
    /** 태스크가 취소된 종단 상태. */
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
