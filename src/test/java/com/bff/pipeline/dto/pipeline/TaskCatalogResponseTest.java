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
 */
class TaskCatalogResponseTest {

    @Test
    void filtersToTheRequestedProvider() {
        List<String> names = TaskCatalogResponse.of(CloudProvider.AWS).taskDefinitions().stream()
                .map(TaskCatalogEntry::name)
                .toList();

        List<String> expected = Arrays.stream(TaskDefinition.values())
                .filter(definition -> definition.provider() == CloudProvider.AWS)
                .map(TaskDefinition::name)
                .toList();

        assertThat(names).isEqualTo(expected);
        assertThat(TaskCatalogResponse.of(CloudProvider.AWS).taskDefinitions())
                .allSatisfy(entry -> assertThat(entry.provider()).isEqualTo(CloudProvider.AWS));
    }

    @Test
    void nullProviderReturnsTheWholeCatalog() {
        assertThat(TaskCatalogResponse.of(null).taskDefinitions())
                .hasSize(TaskDefinition.values().length);
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
