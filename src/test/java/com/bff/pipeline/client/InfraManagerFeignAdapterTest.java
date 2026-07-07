package com.bff.pipeline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.condition.NetworkReadyBinding;
import com.bff.pipeline.client.terraform.IdcTerraformType;
import com.bff.pipeline.client.terraform.TerraformBindingCatalog;
import com.bff.pipeline.client.terraform.TerraformJobType;
import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.DispatchedJob;
import com.bff.pipeline.dto.NetworkReadyResponse;
import com.bff.pipeline.dto.TerraformJobStatusResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 카탈로그 바인딩({@link TerraformBindingCatalog} 전 행 + registry)을 관통한 {@link InfraManagerFeignAdapter}의
 * 응답 변환·라우팅·계약 방어를 검증한다. 전송 예외(FeignException)의 실제 발생은 WireMock 통합테스트가 관통
 * 검증하고, 여기서는 손수 구현한 Feign stub으로 매핑 로직만 본다(이 repo는 Mockito 대신 fake 사용).
 */
class InfraManagerFeignAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── dispatch: job id 정규화 ──

    @Test
    void reserializesDispatchJobIdsAsABareJsonArray() {
        StubFeignClient stub = stub().withDispatchList(jobs(11, 12));

        assertThat(adapter(stub).runTerraform("target-a", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo("[\"11\",\"12\"]");
        assertThat(stub.lastCall).isEqualTo("awsServiceApply");
    }

    @Test
    void promotesASingleDispatchResponseToAJobIdList() {
        StubFeignClient stub = stub().withDispatchSingle(new DispatchedJob(7L));

        assertThat(adapter(stub).runTerraform("target-a", TaskOperation.AWS_BDC_COMMON_TF_APPLY))
                .isEqualTo("[\"7\"]");
    }

    @Test
    void treatsAnEmptyJobIdListAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub().withDispatchList(List.of()));

        assertThatThrownBy(() -> adapter.runTerraform("target-a", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void treatsAMissingSingleDispatchAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub());  // dispatchSingle = null

        assertThatThrownBy(() -> adapter.runTerraform("target-a", TaskOperation.AWS_BDC_SERVICE_LEVEL_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    @Test
    void treatsANullJobIdElementAsACallFailure() {
        // {"id":null} 같은 malformed dispatch 응답이 성공으로 저장돼 poll에서 terminal 실패로 바뀌지 않도록 경계에서 닫는다.
        InfraManagerFeignAdapter adapter = adapter(stub()
                .withDispatchList(List.of(new DispatchedJob(1L), new DispatchedJob(null))));

        assertThatThrownBy(() -> adapter.runTerraform("target-a", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    // ── 라우팅: operation-결정 상수는 Feign default 메서드에 닫혀 있어야 한다 ──

    @Test
    void routesEachOperationToItsOwnApi() {
        StubFeignClient stub = stub().withDispatchList(jobs(1));

        adapter(stub).runTerraform("target-a", TaskOperation.AWS_SERVICE_TF_DESTROY);
        assertThat(stub.lastCall).isEqualTo("awsServiceDestroy");

        adapter(stub).runTerraform("target-a", TaskOperation.AZURE_BDC_TF_APPLY);
        assertThat(stub.lastCall).isEqualTo("azureBdcApply");
    }

    @Test
    void passesTheJobTypeConstantOnSharedEndpoints() {
        StubFeignClient stub = stub().withStatus(status("DESTROYED"));

        adapter(stub).terraformJobStatus("job-9", TaskOperation.GCP_BDC_TF_DESTROY);
        assertThat(stub.lastCall).isEqualTo("gcpBdcJobStatus:DESTROY");
    }

    @Test
    void passesBothIdcConstantsOnDispatch() {
        StubFeignClient stub = stub().withDispatchSingle(new DispatchedJob(3L));

        adapter(stub).runTerraform("target-a", TaskOperation.IDC_BDP_TF_APPLY);
        assertThat(stub.lastCall).isEqualTo("idcDispatch:APPLY:BDP");
    }

    // ── status → TerraformPoll 정규화 (terraformState 매핑, owner 확정 표) ──

    @Test
    void mapsCompletedToASuccessfulPollForPlanAndApply() {
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(status("COMPLETED")));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo(TerraformPoll.success("COMPLETED"));
    }

    @Test
    void mapsDestroyedToASuccessfulPollForDestroy() {
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(status("DESTROYED")));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_DESTROY))
                .isEqualTo(TerraformPoll.success("DESTROYED"));
    }

    @Test
    void mapsFailedToAFailedPoll() {
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(status("FAILED")));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo(TerraformPoll.failure("FAILED", null));
    }

    @Test
    void treatsUnknownStatesAsStillRunning() {
        // TerraformState 전체 목록 미확정(owner) — terminal 세 값 외에는 전부 진행 중. executionTimeout이 상한.
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(status("PLANNING")));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo(TerraformPoll.running("PLANNING"));
    }

    @Test
    void treatsTheWrongTerminalStateAsStillRunning() {
        // apply job이 DESTROYED를 보고하는 불가능 조합 — 성공으로 오인하지 않고 진행 중으로 두면 timeout이 회수한다.
        InfraManagerFeignAdapter adapter = adapter(stub().withStatus(status("DESTROYED")));

        assertThat(adapter.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isEqualTo(TerraformPoll.running("DESTROYED"));
    }

    @Test
    void treatsAMissingOrBlankStateAsACallFailure() {
        InfraManagerFeignAdapter missing = adapter(stub());  // status = null
        InfraManagerFeignAdapter blank = adapter(stub().withStatus(status("  ")));

        assertThatThrownBy(() -> missing.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class)
                .hasMessageContaining("job-1");
        assertThatThrownBy(() -> blank.terraformJobStatus("job-1", TaskOperation.AWS_SERVICE_TF_APPLY))
                .isInstanceOf(CallFailedException.class);
    }

    // ── result (postCheck 관찰 창구) ──

    @Test
    void returnsTheResultBody() {
        StubFeignClient stub = stub().withResult("Plan: 3 to add, 0 to destroy.");

        assertThat(adapter(stub).terraformJobResult("job-1", TaskOperation.AWS_SERVICE_TF_PLAN))
                .isEqualTo("Plan: 3 to add, 0 to destroy.");
        assertThat(stub.lastCall).isEqualTo("awsServicePlanJobResult");
    }

    @Test
    void treatsAMissingResultAsACallFailure() {
        InfraManagerFeignAdapter adapter = adapter(stub());  // result = null

        assertThatThrownBy(() -> adapter.terraformJobResult("job-1", TaskOperation.AWS_SERVICE_TF_PLAN))
                .isInstanceOf(CallFailedException.class)
                .hasMessageContaining("job-1");
    }

    // ── condition / cloud provider ──

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

        assertThat(met.checkCondition("target-a", TaskOperation.NETWORK_READY).met()).isTrue();
        assertThat(notMet.checkCondition("target-a", TaskOperation.NETWORK_READY).met()).isFalse();
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
                TerraformBindingCatalog.rows(stub),
                List.of(new NetworkReadyBinding(stub, objectMapper)));
        return new InfraManagerFeignAdapter(stub, registry, objectMapper);
    }

    private StubFeignClient stub() {
        return new StubFeignClient();
    }

    private static List<DispatchedJob> jobs(long... ids) {
        return Arrays.stream(ids).mapToObj(DispatchedJob::new).toList();
    }

    private static TerraformJobStatusResponse status(String state) {
        return new TerraformJobStatusResponse(state, null);
    }

    /**
     * 응답을 스크립트하는 수동 Feign stub — 모든 raw 엔드포인트가 종류별 공통 필드를 돌려주고, 마지막 호출과
     * 전달된 상수를 {@code lastCall}에 남겨 라우팅을 검증한다. default 메서드는 인터페이스 것을 그대로 쓴다
     * (그게 검증 대상이다 — 상수가 default 메서드에 닫혀 있는가).
     */
    private static final class StubFeignClient implements InfraManagerFeignClient {
        private List<DispatchedJob> dispatchList;
        private DispatchedJob dispatchSingle;
        private TerraformJobStatusResponse status;
        private String result;
        private NetworkReadyResponse ready;
        private CloudProviderResponse provider;
        private String lastCall;

        StubFeignClient withDispatchList(List<DispatchedJob> value) { this.dispatchList = value; return this; }
        StubFeignClient withDispatchSingle(DispatchedJob value) { this.dispatchSingle = value; return this; }
        StubFeignClient withStatus(TerraformJobStatusResponse value) { this.status = value; return this; }
        StubFeignClient withResult(String value) { this.result = value; return this; }
        StubFeignClient withReady(NetworkReadyResponse value) { this.ready = value; return this; }
        StubFeignClient withProvider(CloudProviderResponse value) { this.provider = value; return this; }

        private List<DispatchedJob> list(String call) { lastCall = call; return dispatchList; }
        private DispatchedJob single(String call) { lastCall = call; return dispatchSingle; }
        private TerraformJobStatusResponse status(String call) { lastCall = call; return status; }
        private String result(String call) { lastCall = call; return result; }

        @Override public List<DispatchedJob> awsServicePlan(String targetSourceId) { return list("awsServicePlan"); }
        @Override public List<DispatchedJob> awsServiceApply(String targetSourceId) { return list("awsServiceApply"); }
        @Override public List<DispatchedJob> awsServiceDestroy(String targetSourceId) { return list("awsServiceDestroy"); }
        @Override public TerraformJobStatusResponse awsServicePlanJobStatus(String terraformJobId) { return status("awsServicePlanJobStatus"); }
        @Override public TerraformJobStatusResponse awsServiceApplyJobStatus(String terraformJobId) { return status("awsServiceApplyJobStatus"); }
        @Override public TerraformJobStatusResponse awsServiceDestroyJobStatus(String terraformJobId) { return status("awsServiceDestroyJobStatus"); }
        @Override public String awsServicePlanJobResult(String terraformJobId) { return result("awsServicePlanJobResult"); }
        @Override public String awsServiceApplyJobResult(String terraformJobId) { return result("awsServiceApplyJobResult"); }
        @Override public String awsServiceDestroyJobResult(String terraformJobId) { return result("awsServiceDestroyJobResult"); }

        @Override public DispatchedJob awsBdcCommonPlan(String targetSourceId) { return single("awsBdcCommonPlan"); }
        @Override public DispatchedJob awsBdcCommonApply(String targetSourceId) { return single("awsBdcCommonApply"); }
        @Override public DispatchedJob awsBdcCommonDestroy(String targetSourceId) { return single("awsBdcCommonDestroy"); }
        @Override public TerraformJobStatusResponse awsBdcCommonJobStatus(String terraformJobId, TerraformJobType type) { return status("awsBdcCommonJobStatus:" + type); }
        @Override public String awsBdcCommonJobResult(String terraformJobId, TerraformJobType type) { return result("awsBdcCommonJobResult:" + type); }

        @Override public DispatchedJob awsBdcServiceLevelPlan(String targetSourceId) { return single("awsBdcServiceLevelPlan"); }
        @Override public DispatchedJob awsBdcServiceLevelApply(String targetSourceId) { return single("awsBdcServiceLevelApply"); }
        @Override public DispatchedJob awsBdcServiceLevelDestroy(String targetSourceId) { return single("awsBdcServiceLevelDestroy"); }
        @Override public TerraformJobStatusResponse awsBdcServiceLevelPlanJobStatus(String terraformJobId) { return status("awsBdcServiceLevelPlanJobStatus"); }
        @Override public String awsBdcServiceLevelPlanJobResult(String terraformJobId) { return result("awsBdcServiceLevelPlanJobResult"); }
        @Override public TerraformJobStatusResponse awsBdcServiceLevelActionJobStatus(String terraformJobId, TerraformJobType type) { return status("awsBdcServiceLevelActionJobStatus:" + type); }
        @Override public String awsBdcServiceLevelActionJobResult(String terraformJobId, TerraformJobType type) { return result("awsBdcServiceLevelActionJobResult:" + type); }

        @Override public List<DispatchedJob> gcpServiceDispatch(String targetSourceId, TerraformJobType type) { return list("gcpServiceDispatch:" + type); }
        @Override public TerraformJobStatusResponse gcpServiceJobStatus(String terraformJobId, TerraformJobType type) { return status("gcpServiceJobStatus:" + type); }
        @Override public String gcpServiceJobResult(String terraformJobId, TerraformJobType type) { return result("gcpServiceJobResult:" + type); }

        @Override public List<DispatchedJob> gcpBdcDispatch(String targetSourceId, TerraformJobType type) { return list("gcpBdcDispatch:" + type); }
        @Override public TerraformJobStatusResponse gcpBdcJobStatus(String terraformJobId, TerraformJobType type) { return status("gcpBdcJobStatus:" + type); }
        @Override public String gcpBdcJobResult(String terraformJobId, TerraformJobType type) { return result("gcpBdcJobResult:" + type); }

        @Override public List<DispatchedJob> azureBdcPlan(String targetSourceId) { return list("azureBdcPlan"); }
        @Override public List<DispatchedJob> azureBdcApply(String targetSourceId) { return list("azureBdcApply"); }
        @Override public List<DispatchedJob> azureBdcDestroy(String targetSourceId) { return list("azureBdcDestroy"); }
        @Override public TerraformJobStatusResponse azureBdcPlanJobStatus(String terraformJobId) { return status("azureBdcPlanJobStatus"); }
        @Override public TerraformJobStatusResponse azureBdcApplyJobStatus(String terraformJobId) { return status("azureBdcApplyJobStatus"); }
        @Override public TerraformJobStatusResponse azureBdcDestroyJobStatus(String terraformJobId) { return status("azureBdcDestroyJobStatus"); }
        @Override public String azureBdcPlanJobResult(String terraformJobId) { return result("azureBdcPlanJobResult"); }
        @Override public String azureBdcApplyJobResult(String terraformJobId) { return result("azureBdcApplyJobResult"); }
        @Override public String azureBdcDestroyJobResult(String terraformJobId) { return result("azureBdcDestroyJobResult"); }

        @Override public DispatchedJob idcDispatch(String targetSourceId, TerraformJobType type, IdcTerraformType idcType) { return single("idcDispatch:" + type + ":" + idcType); }
        @Override public TerraformJobStatusResponse idcJobStatus(String terraformJobId, TerraformJobType type) { return status("idcJobStatus:" + type); }
        @Override public String idcJobResult(String terraformJobId, TerraformJobType type) { return result("idcJobResult:" + type); }

        @Override public NetworkReadyResponse networkReady(String target) { return ready; }
        @Override public CloudProviderResponse cloudProvider(String target) { return provider; }
    }
}
