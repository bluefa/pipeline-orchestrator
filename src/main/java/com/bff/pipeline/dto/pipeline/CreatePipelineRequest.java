package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.PipelineType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 파이프라인 실행(P10) 요청 본문이다. type은 INSTALL 또는 DELETE. 와이어 필드는 snake_case 계약을 따른다.
 *
 * {@code tasks}는 선택 필드다(LIN-18). 주면 그 순서·이름대로 custom recipe를 실행하고(비영속), 없으면(null/빈 목록)
 * 종전대로 (provider, type) 카탈로그 recipe를 실행한다 — 하위호환.
 */
public record CreatePipelineRequest(
        @JsonProperty("type") PipelineType type,
        @JsonProperty("tasks") List<CustomTaskRequest> tasks) {
}
