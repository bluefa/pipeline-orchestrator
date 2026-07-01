package com.bff.pipeline.dto;

import java.time.Instant;
import lombok.Builder;

/**
 * REST 레이어가 내려주는 오류 응답 본문이다. BFF swagger의 ErrorMessage 계약과 동형으로 맞춘다 —
 * timestamp(발생 시각), status(HttpStatus 문자열, 예 "404 NOT_FOUND"), code(클라이언트가 파싱하는 안정적 코드
 * ORCHESTRATION_*), message(사람이 읽는 설명), path(요청 경로). 필드명은 모두 단어 하나라 snake_case와 동일하게
 * 직렬화된다.
 */
@Builder
public record ErrorResponse(Instant timestamp, String status, String code, String message, String path) {
}
