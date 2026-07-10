package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Terraform job 조회 응답의 wire DTO다 — 전 패밀리 공통으로 InfraManager의 {@code TerraformJob} 엔티티 직렬화
 * (camelCase, snake_case 네이밍 전략 없음)를 받는다(설계 §3). 폴 정규화에 쓰는 {@code terraformState}와 실패 종결의
 * 사유 텍스트 {@code failReason}을 뽑되, 응답 body 전문을 {@code raw}로 함께 보존한다 — 진행-시점 관찰
 * ({@code terraform_job_state.last_response})이 파싱으로 버려지는 필드까지 그대로 남기기 위함이다.
 *
 * 타입 있는 필드만 받으면 나머지가 유실되므로, 전체를 {@link JsonNode}로 위임 역직렬화(delegating creator)한 뒤
 * 두 필드를 뽑고 {@code node.toString()}(compact JSON, 전 필드 보존)을 raw로 싣는다. 공백·키 순서는 정규화되지만
 * 내용은 온전하다. {@code terraformState}는 String이다 — 전체 값 목록이 미확정이라(설계 §6 TODO) terminal 세 값
 * ({@code COMPLETED}/{@code DESTROYED}/{@code FAILED})만 해석하고 나머지는 진행 중으로 본다.
 */
public record TerraformJobStatusResponse(String terraformState, String failReason, String raw) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static TerraformJobStatusResponse fromBody(JsonNode body) {
        return new TerraformJobStatusResponse(text(body, "terraformState"), text(body, "failReason"), body.toString());
    }

    /** 필드가 없거나 JSON null이면 null — MissingNode.asText()가 ""를 돌려주는 함정을 피한다. */
    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
