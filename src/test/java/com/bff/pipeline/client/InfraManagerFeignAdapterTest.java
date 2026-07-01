package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.dto.ApplyJobStatusResponse;
import com.bff.pipeline.dto.ApplyNetworkResponse;
import com.bff.pipeline.client.condition.NetworkReadyBinding;
import com.bff.pipeline.client.terraform.ApplyNetworkBinding;
import com.bff.pipeline.client.terraform.DestroyNetworkBinding;
import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.DestroyJobStatusResponse;
import com.bff.pipeline.dto.DestroyNetworkResponse;
import com.bff.pipeline.dto.NetworkReadyResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bff.pipeline.exception.CallFailedException;

/**
 * {@link InfraManagerFeignAdapter}의 operation별 응답 변환·계약 방어를 검증한다. 전송 예외(FeignException)의 실제
 * 발생은 WireMock 통합테스트가 관통 검증하고, 여기서는 손수 구현한 Feign stub으로 매핑 로직만 본다.
 */
class InfraManagerFeignAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reserializesDispatchJobIdsAsABareJsonArray() {
        InfraManagerFeignAdapter adapter = adapter(stub().withApply(new ApplyNetworkResponse(List.of("job-1", "job-2"))));

        assertThat(adapter.runTerraform("target-a", TaskOperation.APPLY_NETWORK))
                .isEqualTo("[\"job-1\",\"job-2\"]");
    }

    @Test
    void treatsAnEmptyJobIdListAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub().withApply(new ApplyNetworkResponse(List.of())));

        assertThatThrownBy(() -> adapter.runTerraform("target-a", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void routesDestroyToItsOwnApi() {
        InfraManagerFeignAdapter adapter = adapter(stub().withDestroy(new DestroyNetworkResponse(List.of("job-x"))));

        assertThat(adapter.runTerraform("target-a", TaskOperation.DESTROY_NETWORK)).isEqualTo("[\"job-x\"]");
    }

    @Test
    void mapsStatusResponseToTerraformPoll() {
        InfraManagerFeignAdapter adapter = adapter(stub().withApplyStatus(new ApplyJobStatusResponse(true, true)));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.APPLY_NETWORK)).isEqualTo(TerraformPoll.success());
    }

    @Test
    void routesDestroyStatusToItsOwnApi() {
        InfraManagerFeignAdapter adapter = adapter(stub().withDestroyStatus(new DestroyJobStatusResponse(true, false)));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.DESTROY_NETWORK)).isEqualTo(TerraformPoll.failure());
    }

    @Test
    void treatsAMissingStatusAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub().withApplyStatus(null));

        assertThatThrownBy(() -> adapter.terraformJobStatus("job-1", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(CallFailedException.class)
                .hasMessageContaining("job-1");
    }

    @Test
    void treatsAnImpossiblePollStateAsACallFailure() {
        // {finished:false, succeeded:true}는 TerraformPoll 불변식 위반 → 우리 버그가 아니라 쓸 수 없는 외부 응답.
        InfraManagerFeignAdapter adapter = adapter(stub().withApplyStatus(new ApplyJobStatusResponse(false, true)));

        assertThatThrownBy(() -> adapter.terraformJobStatus("job-9", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(CallFailedException.class)
                .hasMessageContaining("job-9");
    }

    @Test
    void treatsAnIncompleteStatusAsACallFailure() {
        // 누락 필드 응답(예: {})은 Boolean이 null로 디코딩됨 → 조용히 false로 처리하지 않고 CallFailed.
        InfraManagerFeignAdapter adapter = adapter(stub().withApplyStatus(new ApplyJobStatusResponse(null, true)));

        assertThatThrownBy(() -> adapter.terraformJobStatus("job-1", TaskOperation.APPLY_NETWORK))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void treatsAMissingConditionFieldAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub().withReady(new NetworkReadyResponse(null)));

        assertThatThrownBy(() -> adapter.checkCondition("target-a", TaskOperation.NETWORK_READY))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void returnsTheConditionOutcome() {
        InfraManagerFeignAdapter met = adapter(stub().withReady(new NetworkReadyResponse(true)));
        InfraManagerFeignAdapter notMet = adapter(stub().withReady(new NetworkReadyResponse(false)));

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
                .isInstanceOf(CallFailedException.class);
        assertThatThrownBy(() -> missing.cloudProvider("target-a"))
                .isInstanceOf(CallFailedException.class);
    }

    private InfraManagerFeignAdapter adapter(StubFeignClient stub) {
        InfraManagerOperationRegistry registry = new InfraManagerOperationRegistry(
                List.of(new ApplyNetworkBinding(stub), new DestroyNetworkBinding(stub)),
                List.of(new NetworkReadyBinding(stub)));
        return new InfraManagerFeignAdapter(stub, registry, objectMapper);
    }

    private StubFeignClient stub() {
        return new StubFeignClient();
    }

    /** operation별 반환값을 스크립트하는 수동 Feign stub(이 repo는 Mockito 대신 fake 사용). */
    private static final class StubFeignClient implements InfraManagerFeignClient {
        private ApplyNetworkResponse apply;
        private DestroyNetworkResponse destroy;
        private ApplyJobStatusResponse applyStatus;
        private DestroyJobStatusResponse destroyStatus;
        private NetworkReadyResponse ready;
        private CloudProviderResponse provider;

        StubFeignClient withApply(ApplyNetworkResponse value) { this.apply = value; return this; }
        StubFeignClient withDestroy(DestroyNetworkResponse value) { this.destroy = value; return this; }
        StubFeignClient withApplyStatus(ApplyJobStatusResponse value) { this.applyStatus = value; return this; }
        StubFeignClient withDestroyStatus(DestroyJobStatusResponse value) { this.destroyStatus = value; return this; }
        StubFeignClient withReady(NetworkReadyResponse value) { this.ready = value; return this; }
        StubFeignClient withProvider(CloudProviderResponse value) { this.provider = value; return this; }

        @Override public ApplyNetworkResponse applyNetwork(String target) { return apply; }
        @Override public DestroyNetworkResponse destroyNetwork(String target) { return destroy; }
        @Override public ApplyJobStatusResponse applyJobStatus(String jobId) { return applyStatus; }
        @Override public DestroyJobStatusResponse destroyJobStatus(String jobId) { return destroyStatus; }
        @Override public NetworkReadyResponse networkReady(String target) { return ready; }
        @Override public CloudProviderResponse cloudProvider(String target) { return provider; }
    }
}
