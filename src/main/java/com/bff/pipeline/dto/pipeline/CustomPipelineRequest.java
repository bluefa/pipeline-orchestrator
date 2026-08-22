package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * custom recipe 실행 요청 본문이다(LIN-18). 운영자가 구성한 task 순서를 {@code tasks}로 받아 그대로 실행한다(비영속).
 * INSTALL/DELETE 같은 type은 받지 않는다 — custom 실행은 그 자체가 분류이며 저장 시 {@code PipelineType.CUSTOM}이
 * 된다. tasks는 최소 한 개여야 하고(비면 400), 각 task 이름은 {@code TaskDefinition} 기준으로 해석된다.
 *
 * {@code requested_by}/{@code request_note}는 카탈로그 실행과 같은 요청 맥락이다 — 누가 요청했고 무슨 말을
 * 남겼는가. 선택값이라 있으면 기록하고 없으면 그만이다.
 *
 * 와이어 필드는 snake_case 계약을 따른다.
 */
public record CustomPipelineRequest(
        @JsonProperty("tasks") List<CustomTaskRequest> tasks,
        @JsonProperty("requested_by") String requestedBy,
        @JsonProperty("request_note") String requestNote) {
}
