package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.client.InstallationOperationsClient.ConfirmationRequest;
import com.bff.pipeline.client.InstallationOperationsClient.Request;
import com.bff.pipeline.enums.CloudProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 프런트엔드 install-v1 계약의 네 CSP 경로와 AWS 전용 등록 옵션을 외부 경계에서 검증한다.
 * 대상 식별자의 경로 구분 문자는 한 segment 안에 인코딩하며 추천 원문과 요청 키는 재구성하지 않는다.
 */
class InstallationRequestPathTest {
    @ParameterizedTest
    @CsvSource({"AWS,aws-resources", "GCP,gcp-resources", "AZURE,azure-resources", "IDC,idc-resources"})
    void selectsProviderSpecificDeletionRecommendationAndConfirmationPaths(CloudProvider provider, String resourcePath) {
        Request request = request("42", provider);
        String expectedPath = "/install/v1/target-sources/42/" + resourcePath;
        ConfirmationRequest confirmation = ConfirmationRequest.builder().request(request).body("{\"unknown\":1}").build();

        assertThat(request.confirmedResourcePath()).isEqualTo(expectedPath);
        assertThat(request.recommendationPath()).isEqualTo(expectedPath + "/approved-recommendations");
        assertThat(confirmation.confirmationPath()).isEqualTo(expectedPath);
        assertThat(confirmation.body()).isEqualTo("{\"unknown\":1}");
        assertThat(confirmation.request().requestKey()).isEqualTo("request-key");
    }

    @Test
    void appliesNlbOptionOnlyToAwsConfirmationPath() {
        Request request = request("42", CloudProvider.AWS);
        ConfirmationRequest confirmation = ConfirmationRequest.builder().request(request).applyNlbSecurityGroup(true).build();

        assertThat(confirmation.confirmationPath()).isEqualTo("/install/v1/target-sources/42/aws-resources?applyNLBSecurityGroup=true");
        assertThat(request.confirmedResourcePath()).isEqualTo("/install/v1/target-sources/42/aws-resources");
        assertThat(request.recommendationPath()).isEqualTo("/install/v1/target-sources/42/aws-resources/approved-recommendations");
    }

    @ParameterizedTest
    @EnumSource(value = CloudProvider.class, names = "AWS", mode = EnumSource.Mode.EXCLUDE)
    void neverAddsAwsQueryToOtherProviderPaths(CloudProvider provider) {
        Request request = request("42", provider);
        ConfirmationRequest confirmation = ConfirmationRequest.builder().request(request).applyNlbSecurityGroup(true).build();

        assertThat(confirmation.confirmationPath()).isEqualTo(request.confirmedResourcePath()).doesNotContain("?");
    }

    @Test
    void encodesTargetAsOnePathSegment() {
        Request request = request("tenant/a?b#c% 한글", CloudProvider.GCP);

        assertThat(request.confirmedResourcePath()).isEqualTo(
                "/install/v1/target-sources/tenant%2Fa%3Fb%23c%25%20%ED%95%9C%EA%B8%80/gcp-resources");
    }

    private Request request(String target, CloudProvider provider) {
        return Request.builder().taskId(1L).target(target).provider(provider).requestKey("request-key").build();
    }
}
