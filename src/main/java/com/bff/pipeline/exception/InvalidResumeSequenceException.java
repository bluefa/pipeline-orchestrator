package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 재시작 오버라이드 {@code from_sequence}가 범위 밖이다 — 허용 범위는 0(전체 재실행)부터 기본 재시작
 * 지점(첫 non-DONE task)까지다. 더 앞은 DONE task의 멱등 재실행이라 안전하지만, 더 뒤는 실패 task를
 * 건너뛰어 설치 완전성을 깨므로 거절한다(재시작 설계 결정 3). 400 Bad Request + code
 * {@code ORCHESTRATION_INVALID_RESUME_SEQUENCE}로 매핑된다.
 */
public class InvalidResumeSequenceException extends OrchestrationException {

    public InvalidResumeSequenceException(int fromSequence, int defaultResumeSequence) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.INVALID_RESUME_SEQUENCE,
                "from_sequence " + fromSequence + " must be between 0 and " + defaultResumeSequence);
    }
}
