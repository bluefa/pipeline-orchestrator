package com.bff.pipeline.dto.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskDefinition;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TaskDefinition 카탈로그 필터링(LIN-27)을 검증한다. provider가 주어지면 그 provider만, 없으면 전체를 돌려준다.
 * 잘못된 provider 값 거절(400)은 컨트롤러 바인딩 책임이라 여기서 다루지 않는다.
 *
 * 승인 게이트 정의는 어느 경우에도 목록에서 빠진다 — 이 목록의 쓰임이 custom recipe 빌더의 선택지인데
 * 게이트는 거기 넣을 수 없기 때문이다. 빼지 않으면 화면에는 고를 수 있게 보이다가 제출하는 순간에만
 * 거절당하는 어긋난 표면이 된다.
 */
class TaskCatalogResponseTest {

    @Test
    void filtersToTheRequestedProvider() {
        List<String> names = TaskCatalogResponse.of(CloudProvider.AWS).taskDefinitions().stream()
                .map(TaskCatalogEntry::name)
                .toList();

        List<String> expected = Arrays.stream(TaskDefinition.values())
                .filter(definition -> definition.provider() == CloudProvider.AWS)
                .filter(definition -> !definition.isApprovalGate())
                .map(TaskDefinition::name)
                .toList();

        assertThat(names).isEqualTo(expected);
        assertThat(TaskCatalogResponse.of(CloudProvider.AWS).taskDefinitions())
                .allSatisfy(entry -> assertThat(entry.provider()).isEqualTo(CloudProvider.AWS));
    }

    @Test
    void nullProviderReturnsTheWholeCatalogExceptApprovalGates() {
        long selectable = Arrays.stream(TaskDefinition.values())
                .filter(definition -> !definition.isApprovalGate())
                .count();

        assertThat(TaskCatalogResponse.of(null).taskDefinitions()).hasSize((int) selectable);
    }

    /** 승인 게이트는 provider를 지정하든 안 하든 목록에 없다 — custom recipe에 넣을 수 없는 항목이기 때문이다. */
    @Test
    void approvalGatesAreNeverOffered() {
        assertThat(TaskCatalogResponse.of(null).taskDefinitions())
                .extracting(TaskCatalogEntry::kind).doesNotContain("APPROVAL");
        assertThat(TaskCatalogResponse.of(CloudProvider.AWS).taskDefinitions())
                .extracting(TaskCatalogEntry::kind).doesNotContain("APPROVAL");
    }

    @Test
    void carriesTheFieldsTheBuilderNeeds() {
        TaskCatalogEntry conditionCheck = TaskCatalogResponse.of(CloudProvider.AWS).taskDefinitions().stream()
                .filter(entry -> entry.name().equals(TaskDefinition.NETWORK_READY_V1.name()))
                .findFirst().orElseThrow();

        assertThat(conditionCheck.kind()).isEqualTo("CONDITION_CHECK");
        assertThat(conditionCheck.consumesTerraformSlot()).isFalse();
        assertThat(conditionCheck.displayName()).isNotBlank();
    }
}
