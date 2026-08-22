package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskDefinition;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.List;

/**
 * TaskDefinition 카탈로그 조회 응답이다(LIN-27). {@code provider}가 주어지면 그 provider의 Task만, null이면 전체를
 * TaskDefinition 선언 순서대로 담는다. 와이어 필드는 snake_case로 직렬화한다.
 *
 * 승인 게이트 정의는 목록에서 뺀다. 이 카탈로그의 쓰임은 custom recipe 빌더가 고를 수 있는 Task를
 * 채우는 것인데, 승인 게이트는 custom recipe에 넣을 수 없기 때문이다 — 빼지 않으면 화면에는 선택지로
 * 보이다가 실행하려는 순간에만 거절당하는 어긋난 표면이 된다.
 */
public record TaskCatalogResponse(@JsonProperty("task_definitions") List<TaskCatalogEntry> taskDefinitions) {

    /** provider(nullable)로 카탈로그를 필터링해 응답을 만든다. 잘못된 provider 값 거절은 컨트롤러 바인딩(400)이 맡는다. */
    public static TaskCatalogResponse of(CloudProvider provider) {
        List<TaskCatalogEntry> entries = Arrays.stream(TaskDefinition.values())
                .filter(definition -> !definition.isApprovalGate())
                .filter(definition -> provider == null || definition.provider() == provider)
                .map(TaskCatalogEntry::from)
                .toList();
        return new TaskCatalogResponse(entries);
    }
}
