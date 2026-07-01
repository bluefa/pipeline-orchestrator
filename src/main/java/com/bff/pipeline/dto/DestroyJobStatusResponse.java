package com.bff.pipeline.dto;

/**
 * DESTROY_NETWORK 잡 상태 조회의 InfraManager 응답 전송 값이다(operation별 DTO). Boolean(nullable) — 누락 필드는
 * 어댑터가 CallFailed로 닫는다. 실제 스키마 확정 전 placeholder.
 */
public record DestroyJobStatusResponse(Boolean finished, Boolean succeeded) { }
