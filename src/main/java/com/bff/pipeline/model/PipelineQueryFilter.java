package com.bff.pipeline.model;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import lombok.Builder;

/**
 * 파이프라인 목록의 선택적 검색 조건이다. null인 필드는 제한하지 않으며 지정한 조건은 모두 함께 적용한다.
 * recipeDefinition은 과거 카탈로그 이름도 검색할 수 있도록 enum으로 변환하지 않는 정확 일치 문자열이다.
 * 기간의 기준 시각은 조회 서비스가 주입받은 Clock으로 계산하므로 이 값에는 포함하지 않는다.
 */
@Builder
public record PipelineQueryFilter(PipelineStatus status, CloudProvider provider,
        PipelineType type, String recipeDefinition) {
}
