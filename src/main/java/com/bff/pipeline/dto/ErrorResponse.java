package com.bff.pipeline.dto;

import lombok.Builder;

/**
 * REST 레이어가 내려주는 최소 오류 응답 본문이다. 클라이언트가 파싱하는 안정적인 코드 문자열과
 * 사람이 읽는 메시지를 함께 담는다.
 */
@Builder
public record ErrorResponse(String code, String message) {
}
