package com.bff.pipeline.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.condition.NetworkReadyBinding;
import com.bff.pipeline.client.terraform.TerraformBindingCatalog;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import feign.Feign;
import feign.Request;
import feign.codec.ErrorDecoder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 실제 Feign 스택(SpringMvcContract + Spring encoder/decoder + 기본 ErrorDecoder)을 WireMock에 붙여, 실 스펙 경로·
 * 쿼리 파라미터가 wire에 그대로 나가는지와 HTTP 실패가 {@link InfraManagerFeignAdapter}를 통해 닫힌 어휘
 * {@link CallFailedException}으로 귀결되는지 관통 검증한다. 200 정상 / 5xx / malformed 바디 / read timeout 경로.
 */
class InfraManagerFeignIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WireMockServer wireMock;
    private InfraManagerFeignAdapter adapter;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        InfraManagerFeignClient client = feignClient(wireMock.baseUrl(), 500);
        InfraManagerOperationRegistry registry = new InfraManagerOperationRegistry(
                TerraformBindingCatalog.rows(client),
                List.of(new NetworkReadyBinding(client, objectMapper)));
        adapter = new InfraManagerFeignAdapter(client, registry, objectMapper);
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void happyPathMapsTheStatusBody() {
        // 실 응답은 TerraformJob 엔티티 직렬화(camelCase) — 안 쓰는 필드는 무시되어야 한다(ignoreUnknown).
        wireMock.stubFor(get(urlPathEqualTo("/infra/terraform-jobs/apply/job-7"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"terraformState\":\"COMPLETED\",\"type\":\"APPLY\","
                                + "\"resultPath\":\"gs://results/7\",\"cloudAuthIdentity\":\"x\"}")));

        assertThat(adapter.terraformJobStatus("job-7", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo(TerraformPoll.success("gs://results/7"));
    }

    @Test
    void reserializesListDispatchJobIdsEndToEnd() {
        wireMock.stubFor(post(urlPathEqualTo("/infra/target-sources/target-a/terraform-jobs/apply"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":11,\"terraformState\":\"CREATED\"},{\"id\":12}]")));

        assertThat(adapter.runTerraform("target-a", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo("[\"11\",\"12\"]");
    }

    @Test
    void promotesASingleDispatchResponseEndToEnd() {
        wireMock.stubFor(post(urlPathEqualTo("/infra/target-sources/target-a/bdc-service-level-terraform-jobs/apply"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"terraform_state\":\"CREATED\",\"target_source_id\":3}")));

        assertThat(adapter.runTerraform("target-a", TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_APPLY))
                .isEqualTo("[\"7\"]");
    }

    @Test
    void sendsTheJobTypeQueryParamOnSharedEndpoints() {
        wireMock.stubFor(get(urlPathEqualTo("/infra/gcp/bdc-terraform-jobs/job-9"))
                .withQueryParam("jobType", equalTo("DESTROY"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"terraformState\":\"DESTROYED\"}")));

        assertThat(adapter.terraformJobStatus("job-9", TaskOperation.GCP_BDC_TF_DESTROY))
                .isEqualTo(TerraformPoll.success());
    }

    @Test
    void sendsBothIdcQueryParamsOnDispatch() {
        wireMock.stubFor(post(urlPathEqualTo("/infra/target-sources/target-a/idc/terraform/action"))
                .withQueryParam("jobType", equalTo("APPLY"))
                .withQueryParam("idcTerraformType", equalTo("BDP"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":3}")));

        assertThat(adapter.runTerraform("target-a", TaskOperation.IDC_BDP_TF_APPLY)).isEqualTo("[\"3\"]");
    }

    @Test
    void returnsTheResultBodyEndToEnd() {
        wireMock.stubFor(get(urlPathEqualTo("/infra/terraform-jobs/plan/job-7/result"))
                .willReturn(aResponse().withHeader("Content-Type", "text/plain")
                        .withBody("Plan: 3 to add, 0 to change, 0 to destroy.")));

        assertThat(adapter.terraformJobResult("job-7", TaskOperation.AWS_SERVICE_TF_PLAN))
                .isEqualTo("Plan: 3 to add, 0 to change, 0 to destroy.");
    }

    @Test
    void parsesCloudProviderEndToEnd() {
        wireMock.stubFor(get(urlPathMatching("/infra/targets/.*/cloud-provider"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"provider\":\"GCP\"}")));

        assertThat(adapter.cloudProvider("target-a")).isEqualTo(CloudProvider.GCP);
    }

    @Test
    void serverErrorBecomesACallFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/infra/terraform-jobs/apply/job-7"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter.terraformJobStatus("job-7", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void malformedBodyBecomesACallFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/infra/terraform-jobs/apply/job-7"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("not-json")));

        assertThatThrownBy(() -> adapter.terraformJobStatus("job-7", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void readTimeoutBecomesACallFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/infra/terraform-jobs/apply/job-7"))
                .willReturn(aResponse().withFixedDelay(1000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"terraformState\":\"COMPLETED\"}")));

        // 이 클라이언트의 readTimeout은 500ms(@BeforeEach) — 1000ms 지연 응답은 RetryableException → CallFailed 로 귀결.
        assertThatThrownBy(() -> adapter.terraformJobStatus("job-7", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void sendsBearerAuthorizationHeader() {
        wireMock.stubFor(get(urlPathEqualTo("/infra/terraform-jobs/apply/job-7"))
                .withHeader("Authorization", matching("Bearer test-token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"terraformState\":\"CREATING\"}")));

        assertThat(adapter.terraformJobStatus("job-7", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo(TerraformPoll.running());
    }

    private InfraManagerFeignClient feignClient(String baseUrl, int readTimeoutMillis) {
        ObjectFactory<HttpMessageConverters> converters = () -> new HttpMessageConverters(
                new MappingJackson2HttpMessageConverter(objectMapper), new StringHttpMessageConverter());
        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new SpringEncoder(converters))
                .decoder(new SpringDecoder(converters))
                .errorDecoder(new ErrorDecoder.Default())
                .requestInterceptor(template -> template.header("Authorization", "Bearer test-token"))
                .options(new Request.Options(300, TimeUnit.MILLISECONDS, readTimeoutMillis, TimeUnit.MILLISECONDS, true))
                .target(InfraManagerFeignClient.class, baseUrl);
    }
}
