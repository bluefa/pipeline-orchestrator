package com.bff.pipeline.dto;

import java.util.List;

/**
 * APPLY_NETWORK dispatch의 InfraManager 응답 전송 값이다(operation마다 실제 API·응답이 다르므로 operation별 DTO).
 * 어댑터가 여기서 job id 목록을 뽑아 도메인 공통 형식(bare {@code ["job-1",...]} JSON 문자열)으로 정규화한다.
 * 실제 스키마 확정 전 placeholder — 필드는 실제 API에 맞춰 조정한다.
 */
public record ApplyNetworkResponse(List<String> jobIds) { }
