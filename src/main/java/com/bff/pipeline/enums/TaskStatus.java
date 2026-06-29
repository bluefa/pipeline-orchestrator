package com.bff.pipeline.enums;

/**
 * 태스크 생명주기를 나타낸다(ADR-016 §2).
 *
 * <pre>
 *   BLOCKED ──▶ READY ──▶ IN_PROGRESS ──▶ DONE | FAILED | CANCELLED
 * </pre>
 *
 * 태스크는 생성 시 {@code BLOCKED} 상태로 시작하며(파이프라인의 첫 번째 태스크는 예외적으로
 * {@code READY}로 생성된다), 선행 태스크가 {@code DONE} 상태가 되면 {@code READY}로 전환된다.
 * <em>현재 태스크(current task)</em>는 가장 낮은 순번의 {@code READY}/{@code IN_PROGRESS} 태스크이며,
 * 그 앞에 있는 태스크들은 {@code BLOCKED} 상태를 유지한다.
 */
public enum TaskStatus {
    /** 선행 태스크가 완료되지 않아 아직 실행될 수 없는 상태. */
    BLOCKED,
    /** 선행 조건이 충족되어 실행 준비가 완료된 상태. */
    READY,
    /** 현재 dispatch 또는 poll이 진행 중인 상태. */
    IN_PROGRESS,
    /** 태스크가 성공적으로 완료된 종단 상태. */
    DONE,
    /** 태스크가 실패로 종료된 종단 상태. */
    FAILED,
    /** 태스크가 취소된 종단 상태. */
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
