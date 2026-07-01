package com.bff.pipeline.dto;

import java.util.List;

/**
 * DESTROY_NETWORK dispatch의 InfraManager 응답 전송 값이다(operation별 DTO). 어댑터가 job id 목록을 뽑아
 * 도메인 공통 형식으로 정규화한다. 실제 스키마 확정 전 placeholder.
 */
public record DestroyNetworkResponse(List<String> jobIds) { }
