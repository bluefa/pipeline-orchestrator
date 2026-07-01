package com.bff.pipeline.dto;

/**
 * APPLY_NETWORK 잡 상태 조회의 InfraManager 응답 전송 값이다(operation별 DTO). Boolean(nullable)이라 누락 필드
 * 응답이 조용히 false로 디코딩되지 않는다 — 어댑터가 null이면 CallFailed로 닫고, 유효하면 TerraformPoll로 변환한다.
 * 실제 스키마 확정 전 placeholder.
 */
public record ApplyJobStatusResponse(Boolean finished, Boolean succeeded) { }
