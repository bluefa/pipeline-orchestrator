package com.bff.pipeline.model;

import java.time.Instant;
import lombok.Builder;

/**
 * HTTP 응답 한 건의 본문과 진단 정보를 전달한다. 본문은 JSON 재직렬화 전 문자열이며 전송 실패의 응답 없음은
 * 이 값 전체의 null로 표현한다. 저장 전 크기 제한을 적용하며 정체성 검증에는 잘린 진단 문자열을 사용하지 않는다.
 */
@Builder(toBuilder = true)
public record HttpExchange(String operation, Integer statusCode, String contentType, String body,
        Instant receivedAt, boolean truncated) { }
