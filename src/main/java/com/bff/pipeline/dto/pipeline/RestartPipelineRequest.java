package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 재시작 실행 요청 본문이다(본문 자체가 생략 가능). {@code from_sequence}는 재시작 지점 오버라이드로,
 * null이면 서버가 계산한 기본 지점(원본 체인의 첫 non-DONE task)부터다. 0 이상 기본 지점 이하만
 * 허용된다 — 더 뒤(실패 task 건너뛰기)는 400이다(재시작 설계 결정 3).
 *
 * {@code requested_by}/{@code request_note}는 이 재시작의 요청 맥락이다. 재시작 버튼을 누른 사람이 새
 * 요청자이므로 값을 실어 보내면 그것이 쓰이고, 둘 다 비워 두면 원본 실행의 요청 맥락을 그대로 승계한다 —
 * 승인 단계가 포함된 체인을 다시 돌리는데 요청자가 비어 버리는 구멍을 막는다.
 */
public record RestartPipelineRequest(
        @JsonProperty("from_sequence") Integer fromSequence,
        @JsonProperty("requested_by") String requestedBy,
        @JsonProperty("request_note") String requestNote) {
}
