package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.ConditionResponse;
import com.bff.pipeline.dto.TerraformDispatchResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.TerraformStatusResponse;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link InfraManagerFeignAdapter}의 응답 변환·계약 방어를 검증한다. 전송 예외(FeignException/RetryableException)의
 * 실제 발생은 WireMock 통합테스트가 관통 검증하고, 여기서는 손수 구현한 Feign stub으로 매핑 로직만 본다.
 */
class InfraManagerFeignAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reserializesDispatchJobIdsAsABareJsonArray() {
        InfraManagerFeignAdapter adapter = adapter(stub().withDispatch(
                new TerraformDispatchResponse(List.of("job-1", "job-2"))));

        assertThat(adapter.runTerraform("target-a", TaskOperation.APPLY_NETWORK))
                .isEqualTo("[\"job-1\",\"job-2\"]");
    }

    @Test
    void treatsAnEmptyJobIdListAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub().withDispatch(
                new TerraformDispatchResponse(List.of())));

        assertThatThrownBy(() -> adapter.runTerraform("target-a", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(InfraManagerClient.CallFailedException.class);
    }

    @Test
    void mapsStatusResponseToTerraformPoll() {
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(new TerraformStatusResponse(true, true)));

        assertThat(adapter.terraformJobStatus("job-1")).isEqualTo(TerraformPoll.success());
    }

    @Test
    void treatsAMissingStatusAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(null));

        assertThatThrownBy(() -> adapter.terraformJobStatus("job-1"))
                .isInstanceOf(InfraManagerClient.CallFailedException.class)
                .hasMessageContaining("job-1");
    }

    @Test
    void returnsTheConditionOutcome() {
        InfraManagerFeignAdapter met = adapter(stub().withCondition(new ConditionResponse(true)));
        InfraManagerFeignAdapter notMet = adapter(stub().withCondition(new ConditionResponse(false)));

        assertThat(met.checkCondition("target-a", TaskOperation.NETWORK_READY)).isTrue();
        assertThat(notMet.checkCondition("target-a", TaskOperation.NETWORK_READY)).isFalse();
    }

    @Test
    void parsesTheCloudProviderName() {
        InfraManagerFeignAdapter adapter = adapter(stub().withProvider(new CloudProviderResponse("AWS")));

        assertThat(adapter.cloudProvider("target-a")).isEqualTo(CloudProvider.AWS);
    }

    @Test
    void treatsAnUnknownCloudProviderAsACallFailure() {
        InfraManagerFeignAdapter bogus = adapter(stub().withProvider(new CloudProviderResponse("MARS")));
        InfraManagerFeignAdapter missing = adapter(stub().withProvider(new CloudProviderResponse(null)));

        assertThatThrownBy(() -> bogus.cloudProvider("target-a"))
                .isInstanceOf(InfraManagerClient.CallFailedException.class);
        assertThatThrownBy(() -> missing.cloudProvider("target-a"))
                .isInstanceOf(InfraManagerClient.CallFailedException.class);
    }

    private InfraManagerFeignAdapter adapter(StubFeignClient stub) {
        return new InfraManagerFeignAdapter(stub, objectMapper);
    }

    private StubFeignClient stub() {
        return new StubFeignClient();
    }

    /** 반환값만 스크립트하는 수동 Feign stub(이 repo는 Mockito 대신 fake 사용). */
    private static final class StubFeignClient implements InfraManagerFeignClient {
        private TerraformDispatchResponse dispatch;
        private TerraformStatusResponse status;
        private ConditionResponse condition;
        private CloudProviderResponse provider;

        StubFeignClient withDispatch(TerraformDispatchResponse value) { this.dispatch = value; return this; }
        StubFeignClient withStatus(TerraformStatusResponse value) { this.status = value; return this; }
        StubFeignClient withCondition(ConditionResponse value) { this.condition = value; return this; }
        StubFeignClient withProvider(CloudProviderResponse value) { this.provider = value; return this; }

        @Override public TerraformDispatchResponse runTerraform(TaskOperation operation, String target) { return dispatch; }
        @Override public TerraformStatusResponse terraformJobStatus(String jobId) { return status; }
        @Override public ConditionResponse checkCondition(TaskOperation operation, String target) { return condition; }
        @Override public CloudProviderResponse cloudProvider(String target) { return provider; }
    }
}
