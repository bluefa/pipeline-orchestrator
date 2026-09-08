package com.bff.pipeline.enums;

import java.util.Optional;

/**
 * 태스크 실패 원인을 담는 정식 열거형으로, DB에 영속된다(ADR-016 §6). 각 값은 하나의 구체적 원인이지
 * 여러 원인을 뭉뚱그린 버킷이 아니다. 비즈니스 실패를 표현하는 "메시지"에 해당하며, 태스크가 실패하면
 * 예외를 던지는 대신 자기 행(row)에 {@code ErrorCode}를 남긴다(자세한 내용은
 * {@code docs/exception-strategy.md} 참조).
 *
 * 원인별 의미: {@code JOB_FAILED} — Terraform 또는 연결 테스트가 실행 실패로 보고됨.
 * {@code EXECUTION_TIMEOUT} — Terraform 또는 연결 테스트가 태스크별 실행 제한 시간을 넘김.
 * {@code CONDITION_NOT_MET} — CONDITION_CHECK가 maxFailCount 안에 충족되지 못함(마지막 폴이 not-met).
 * {@code CHECK_ERROR} — dispatch/poll 호출이 오류를 반환(잡 실패가 아닌 읽기 실패).
 * {@code CALL_TIMEOUT} — InfraManager 또는 설치 서비스 호출 한 번이 호출별 타임아웃을 넘김.
 * {@code UNKNOWN_TASK} — 태스크에 저장된 이름에 맞는 {@code TaskType}이 등록돼 있지 않아 더는 정의된 태스크가 아님.
 */
public enum ErrorCode {
    /** Terraform 또는 연결 테스트 폴링에서 실행 실패로 보고된 경우. */
    JOB_FAILED,
    /** Terraform 또는 연결 테스트가 태스크별 실행 제한 시간을 넘긴 경우. */
    EXECUTION_TIMEOUT,
    /** CONDITION_CHECK가 maxFailCount 안에 충족되지 못한 경우(재시도 예산 소진, 마지막 폴이 not-met). */
    CONDITION_NOT_MET,
    /** dispatch/poll 호출이 오류를 반환한 경우(잡 실패가 아닌 읽기 실패). */
    CHECK_ERROR,
    /** 외부 호출 한 번이 호출별 타임아웃을 넘긴 경우. */
    CALL_TIMEOUT,
    /** 저장된 태스크 이름에 맞는 {@code TaskType}이 등록돼 있지 않은 경우. */
    UNKNOWN_TASK,
    /** Task 실행에 필요한 영속 입력행이 유실된 경우. */
    EXECUTION_INPUT_MISSING,
    /** 실행 입력이나 CSP 맥락이 유효하지 않은 경우. */
    EXECUTION_INPUT_INVALID,
    /** 성공 응답이 실행 입력 또는 원문 보존의 바이트 상한을 넘긴 경우. */
    RESPONSE_TOO_LARGE,
    /** 추천 정보 조회가 대상 없음으로 응답한 경우. */
    RECOMMENDATION_NOT_FOUND,
    /** 확정정보 변경이 승인 또는 세대 충돌로 거절된 경우. */
    CONFIRMATION_CONFLICT,
    /** 실행 시 해당 CSP의 설치 operation capability가 없는 경우. */
    OPERATION_UNAVAILABLE,
    /** 요청 키와 외부 실행 version의 상관관계를 검증할 수 없는 경우. */
    EXECUTION_CORRELATION_FAILED;

    /**
     * 저장된 error_code 이름(String)을 상수로 해석한다. 미해석(카탈로그에서 제거/rename된 옛 값)은 예외 대신
     * empty를 돌려주어 read가 터지지 않게 한다 — TaskOperation.find/TaskDefinition.find와 같은 열화 규약이며,
     * ErrorCodeConverter가 이 해석으로 행을 읽는다.
     */
    public static Optional<ErrorCode> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ErrorCode.valueOf(name));
        } catch (IllegalArgumentException notAnErrorCode) {
            return Optional.empty();
        }
    }
}
