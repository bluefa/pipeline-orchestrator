package com.bff.pipeline.dto;

/**
 * REST 레이어를 위한 최소 오류 응답 본문이다. 클라이언트가 파싱할 수 있는 안정적인 코드 문자열과
 * 사람이 읽을 수 있는 메시지를 포함한다.
 */
public record ErrorResponse(String code, String message) {
}
