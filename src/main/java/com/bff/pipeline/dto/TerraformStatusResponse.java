package com.bff.pipeline.dto;

/**
 * InfraManager의 terraform job 상태 조회 응답 전송 값이다. 필드를 {@code Boolean}(nullable)로 두어 누락 필드
 * 응답(예: {@code {}})이 조용히 {@code false}로 디코딩되지 않게 한다 — {@code InfraManagerFeignAdapter}가 null이면
 * 쓸 수 없는 응답으로 보고 CallFailed로 닫는다. 유효한 응답은 {@link TerraformPoll}로 변환한다.
 */
public record TerraformStatusResponse(Boolean finished, Boolean succeeded) { }
