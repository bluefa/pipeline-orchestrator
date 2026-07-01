package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.PipelineType;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 파이프라인 실행(P10) 요청 본문이다. type은 INSTALL 또는 DELETE. 와이어 필드는 snake_case 계약을 따른다. */
public record CreatePipelineRequest(@JsonProperty("type") PipelineType type) {
}
