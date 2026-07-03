package com.bff.pipeline.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * custom recipe 실행 요청의 한 Task다(LIN-18). {@code name}은 TaskDefinition 상수 이름이고(서버가 그 이름으로
 * 해석), {@code description}은 선택적 운영자 설명(최대 {@link #MAX_DESCRIPTION_LENGTH}자)으로 실행 기록에 저장돼
 * 상세 조회에서 노출된다. 와이어 필드는 snake_case 계약을 따른다.
 */
public record CustomTaskRequest(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description) {

    public static final int MAX_DESCRIPTION_LENGTH = 100;
}
