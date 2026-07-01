package com.bff.pipeline.dto;

/**
 * InfraManager의 targetSourceId → cloud provider 조회 응답 전송 값이다. {@code provider}는 CloudProvider enum
 * 이름 문자열(AWS/GCP/AZURE/IDC)이며, 매칭되지 않는 값은 어댑터가 호출 실패로 처리한다.
 */
public record CloudProviderResponse(String provider) { }
