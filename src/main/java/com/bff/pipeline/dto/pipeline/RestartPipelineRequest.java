package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 재시작 실행 요청 본문이다(본문 자체가 생략 가능). {@code from_sequence}는 재시작 지점 오버라이드로,
 * null이면 서버가 계산한 기본 지점(원본 체인의 첫 non-DONE task)부터다. 0 이상 기본 지점 이하만
 * 허용된다 — 더 뒤(실패 task 건너뛰기)는 400이다(재시작 설계 결정 3).
 */
public record RestartPipelineRequest(@JsonProperty("from_sequence") Integer fromSequence) {
}
