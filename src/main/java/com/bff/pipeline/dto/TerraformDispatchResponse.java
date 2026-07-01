package com.bff.pipeline.dto;

import java.util.List;

/**
 * InfraManager의 terraform dispatch 응답 전송 값이다. 한 번의 dispatch가 만든 job id 집합을 담는다.
 * {@code InfraManagerFeignAdapter}가 이 {@code jobIds}만 bare JSON 배열 문자열(예: {@code ["job-1","job-2"]})로
 * 재직렬화해 도메인에 넘긴다 — {@code TerraformTask}가 그 형식을 {@code List<String>}으로 역직렬화하기 때문이다.
 */
public record TerraformDispatchResponse(List<String> jobIds) { }
