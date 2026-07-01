package com.bff.pipeline.enums;

/**
 * 셀 만한 값이 있는 폴링 한 번의 관찰 결과다(ADR-016 §3). 디버깅용일 뿐이라, 종단 {@code DONE}/{@code FAILED}
 * 결과는 이 열거형이 아니라 attempt에 남는다. 아직 대기 중인 폴은 {@code TaskType#check}가
 * {@link #RUNNING}/{@link #NOT_MET}으로 보고하고, 조건이 충족된 폴은 {@link #MET}으로, 호출 자체가 실패하면
 * 엔진이 {@link #API_ERROR}/{@link #CALL_TIMEOUT}으로 보고한다.
 *
 * <p>{@code RUNNING} — terraform 잡이 아직 도는 중. {@code MET} — 조건이 충족됨(그 폴로 task DONE).
 * {@code NOT_MET} — 조건이 아직 안 맞음. {@code API_ERROR} — poll 호출이 오류를 반환.
 * {@code CALL_TIMEOUT} — poll 호출이 호출별 타임아웃을 넘김.
 */
public enum CheckSignal {
    /** terraform 잡이 아직 도는 중이라는 폴링 관찰값. */
    RUNNING,
    /** 조건 체크(condition check)의 조건이 충족됐다는 관찰값(그 폴로 task가 DONE 처리됨). */
    MET,
    /** 조건 체크(condition check)의 조건이 아직 안 맞았다는 관찰값. */
    NOT_MET,
    /** poll 호출이 오류를 반환한 경우(잡 실패가 아닌 읽기 실패). */
    API_ERROR,
    /** poll 호출이 호출별 타임아웃을 넘긴 경우. */
    CALL_TIMEOUT
}
