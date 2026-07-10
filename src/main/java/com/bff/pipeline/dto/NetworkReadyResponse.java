package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * NETWORK_READY 조건 탐색의 InfraManager 응답 전송 값이다(operation별 DTO). 충족 여부 {@code met}(nullable —
 * 누락 필드는 어댑터가 CallFailed로 닫는다)을 뽑되, 응답 body 전문을 {@code raw}로 함께 보존한다 — 그 폴의
 * {@code task_attempt.response}에 파싱으로 버려지는 필드까지 그대로 남기기 위함이다. 전체를 {@link JsonNode}로
 * 위임 역직렬화한 뒤 {@code met}을 뽑고 {@code node.toString()}(compact JSON, 전 필드 보존)을 raw로 싣는다.
 * 실제 스키마 확정 전 placeholder.
 */
public record NetworkReadyResponse(Boolean met, String raw) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static NetworkReadyResponse fromBody(JsonNode body) {
        JsonNode met = body.get("met");
        // 진짜 JSON boolean만 인정한다 — 문자열/숫자 등 잘못된 타입은 met=null로 흘려 어댑터가 CallFailed로 닫게 한다.
        // asBoolean()의 관대한 강제 변환을 쓰면 {"met":"nope"} 같은 malformed 응답이 NOT_MET 비즈니스 결과로 둔갑한다.
        Boolean value = met != null && met.isBoolean() ? met.booleanValue() : null;
        return new NetworkReadyResponse(value, body.toString());
    }
}
