package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Terraform job 조회 응답의 wire DTO다 — 전 패밀리 공통으로 InfraManager의 {@code TerraformJob} 엔티티 직렬화
 * (camelCase, snake_case 네이밍 전략 없음)를 받는다(설계 §3). 폴 정규화에 쓰는 {@code terraformState}와 postCheck
 * 관찰(확장 A)에 쓰는 {@code resultPath}·{@code failReason}만 읽고 나머지는 무시한다.
 *
 * <p>{@code terraformState}는 String이다 — 전체 값 목록이 미확정이라(설계 §6 TODO) terminal 세 값
 * ({@code COMPLETED}/{@code DESTROYED}/{@code FAILED})만 해석하고 나머지는 진행 중으로 본다. 목록이 확정되면
 * 닫힌 enum으로 교체한다(교체 지점은 이 DTO와 TerraformOperationBinding.toPoll 두 곳뿐).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerraformJobStatusResponse(String terraformState, String failReason, String resultPath) {
}
