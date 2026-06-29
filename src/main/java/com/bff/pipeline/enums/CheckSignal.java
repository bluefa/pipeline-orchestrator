package com.bff.pipeline.enums;

/**
 * 카운트할 가치가 있는 단일 폴링 관찰값을 나타낸다(ADR-016 §3). 디버깅 목적으로만 사용되며,
 * 종단 {@code DONE}/{@code FAILED} 결과는 이 열거형이 아닌 attempt에 기록된다.
 * {@code TaskType#check}는 아직 대기 중인 폴에 대해 {@link #RUNNING}/{@link #NOT_MET}을 보고하고,
 * 엔진은 실패한 호출에 대해 {@link #API_ERROR}/{@link #CALL_TIMEOUT}을 보고한다.
 *
 * <p>{@code RUNNING} — terraform 잡이 아직 실행 중임; {@code NOT_MET} — 조건이 아직 충족되지 않음;
 * {@code API_ERROR} — poll 호출이 오류를 반환함; {@code CALL_TIMEOUT} — poll 호출이 호출별 타임아웃을 초과함.
 */
public enum CheckSignal {
    /** terraform 잡이 아직 실행 중임을 나타내는 폴링 관찰값. */
    RUNNING,
    /** 조건 체크(condition check)의 조건이 아직 충족되지 않음을 나타내는 관찰값. */
    NOT_MET,
    /** poll 호출이 오류를 반환한 경우(읽기 실패). */
    API_ERROR,
    /** poll 호출이 호출별 타임아웃을 초과한 경우. */
    CALL_TIMEOUT
}
