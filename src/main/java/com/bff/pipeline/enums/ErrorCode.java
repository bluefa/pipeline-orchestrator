package com.bff.pipeline.enums;

/**
 * 태스크 실패 원인을 담는 정식 열거형으로, DB에 영속된다(ADR-016 §6). 각 값은 하나의 구체적 원인이지
 * 여러 원인을 뭉뚱그린 버킷이 아니다. 비즈니스 실패를 표현하는 "메시지"에 해당하며, 태스크가 실패하면
 * 예외를 던지는 대신 자기 행(row)에 {@code ErrorCode}를 남긴다(자세한 내용은
 * {@code docs/exception-strategy.md} 참조).
 *
 * <p>원인별 의미: {@code JOB_FAILED} — TERRAFORM_JOB 폴링에서 잡이 FAILED로 보고됨.
 * {@code EXECUTION_TIMEOUT} — TERRAFORM_JOB이 태스크별 실행 타임아웃을 넘김.
 * {@code CONDITION_NOT_MET} — CONDITION_CHECK가 maxFailCount 안에 충족되지 못함(마지막 폴이 not-met).
 * {@code CHECK_ERROR} — dispatch/poll 호출이 오류를 반환(잡 실패가 아닌 읽기 실패).
 * {@code CALL_TIMEOUT} — InfraManager 호출 한 번이 호출별 타임아웃을 넘김.
 * {@code UNKNOWN_TASK} — 태스크에 저장된 이름에 맞는 {@code TaskType}이 등록돼 있지 않아 더는 정의된 태스크가 아님.
 */
public enum ErrorCode {
    /** TERRAFORM_JOB 폴링에서 잡이 FAILED로 보고된 경우. */
    JOB_FAILED,
    /** TERRAFORM_JOB이 태스크별 실행 타임아웃을 넘긴 경우. */
    EXECUTION_TIMEOUT,
    /** CONDITION_CHECK가 maxFailCount 안에 충족되지 못한 경우(재시도 예산 소진, 마지막 폴이 not-met). */
    CONDITION_NOT_MET,
    /** dispatch/poll 호출이 오류를 반환한 경우(잡 실패가 아닌 읽기 실패). */
    CHECK_ERROR,
    /** InfraManager 호출 한 번이 호출별 타임아웃을 넘긴 경우. */
    CALL_TIMEOUT,
    /** 저장된 태스크 이름에 맞는 {@code TaskType}이 등록돼 있지 않은 경우. */
    UNKNOWN_TASK
}
