package com.bff.pipeline.dto;

/**
 * InfraManager의 조건 탐색 응답 전송 값이다. {@code met}이 조건 충족 여부다. {@code Boolean}(nullable)로 두어
 * 누락 필드 응답이 조용히 {@code false}로 디코딩되지 않게 한다 — 어댑터가 null이면 CallFailed로 닫는다.
 */
public record ConditionResponse(Boolean met) { }
