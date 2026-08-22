package com.bff.pipeline.service.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.config.ApprovalSettings;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.RecipeDefinition;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 승인 게이트 설정이 (provider, type) 조합마다 어느 레시피를 활성으로 고르는지 고정한다. 조합당 활성 레시피가
 * 정확히 하나라는 규칙은 그대로 유지돼야 하고, 게이트 변형이 없는 조합은 설정과 무관하게 원래 레시피여야
 * 한다 — 후자가 깨지면 승인을 켜는 순간 Azure·IDC·삭제 실행이 통째로 만들어지지 않는다.
 */
class RecipeCatalogTest {

    @Test
    void approvalOffKeepsTheRecipesWithoutAGate() {
        RecipeCatalog catalog = catalogWithApproval(false);

        assertThat(catalog.forProviderAndType(CloudProvider.AWS, PipelineType.INSTALL))
                .contains(RecipeDefinition.AWS_INSTALL_V1);
        assertThat(catalog.forProviderAndType(CloudProvider.GCP, PipelineType.INSTALL))
                .contains(RecipeDefinition.GCP_INSTALL_V1);
    }

    @Test
    void approvalOnSwitchesTheGatedCombinationsOnly() {
        RecipeCatalog catalog = catalogWithApproval(true);

        assertThat(catalog.forProviderAndType(CloudProvider.AWS, PipelineType.INSTALL))
                .contains(RecipeDefinition.AWS_INSTALL_WITH_ADMIN_CONSENT_V1);
        assertThat(catalog.forProviderAndType(CloudProvider.GCP, PipelineType.INSTALL))
                .contains(RecipeDefinition.GCP_INSTALL_WITH_ADMIN_CONSENT_V1);
    }

    /** 게이트 변형이 없는 조합은 설정을 켜도 원래 레시피 그대로여야 한다 — 아니면 그 조합의 실행이 사라진다. */
    @Test
    void combinationsWithoutAGatedVariantAreUnaffectedByTheSetting() {
        for (boolean approvalEnabled : new boolean[] {false, true}) {
            RecipeCatalog catalog = catalogWithApproval(approvalEnabled);
            for (CloudProvider provider : CloudProvider.values()) {
                for (PipelineType type : new PipelineType[] {PipelineType.INSTALL, PipelineType.DELETE}) {
                    assertThat(catalog.forProviderAndType(provider, type))
                            .as("(%s, %s) approval=%s", provider, type, approvalEnabled)
                            .isPresent();
                }
            }
        }
    }

    /** 등록에서 빠진 변형도 이름 해석은 계속 된다 — 진행 중·지난 실행에 저장된 이름이 표시에서 깨지면 안 된다. */
    @Test
    void theInactiveVariantIsStillResolvableByName() {
        catalogWithApproval(false);

        assertThat(RecipeDefinition.find("AWS_INSTALL_WITH_ADMIN_CONSENT_V1"))
                .contains(RecipeDefinition.AWS_INSTALL_WITH_ADMIN_CONSENT_V1);
    }

    private static RecipeCatalog catalogWithApproval(boolean enabled) {
        return new RecipeCatalog(
                ApprovalSettings.builder().enabled(enabled).timeout(Duration.ofHours(24)).build());
    }
}
