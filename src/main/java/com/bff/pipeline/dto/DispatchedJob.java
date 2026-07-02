package com.bff.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Terraform dispatch 응답의 wire DTO다 — 모든 dispatch 엔드포인트를 이 하나로 받는다. 실 스펙에서 dispatch 응답은
 * 목록형({@code List<TerraformJobResponse>})과 단건형({@code BdcTerraformJobResponse}) 둘뿐이고, 우리가 읽는 필드는
 * 어느 쪽이든 job id({@code id}) 하나다(설계 §3). 나머지 필드(created_at, terraform_state, fail_reason 등)는
 * 도메인이 쓰지 않으므로 역직렬화에서 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchedJob(Long id) {
}
