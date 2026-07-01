package com.bff.pipeline.dto;

/**
 * InfraManager의 조건 탐색 응답 전송 값이다. {@code met}이 조건 충족 여부다.
 */
public record ConditionResponse(boolean met) { }
