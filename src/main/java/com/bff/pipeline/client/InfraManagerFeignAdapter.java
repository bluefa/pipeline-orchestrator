package com.bff.pipeline.client;

import com.bff.pipeline.dto.CloudProviderResponse;
import com.bff.pipeline.dto.ConditionResponse;
import com.bff.pipeline.dto.TerraformDispatchResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.dto.TerraformStatusResponse;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link InfraManagerFeignClient}(raw HTTP)를 감싸 도메인이 요구하는 {@link InfraManagerClient} 계약으로 변환하는
 * 프로덕션 delegate다. {@code FeignConfig}가 이 인스턴스를 {@code infraManagerDelegate} 빈으로 등록하면, 기존
 * {@link TimeBoundedInfraManagerClient} 데코레이터가 활성화되어 도메인은 데코레이터를 주입받는다.
 *
 * <p><b>예외 경계.</b> Feign이 던지는 전송 실패({@link FeignException} — HTTP 4xx/5xx, {@link feign.RetryableException} —
 * 연결 거부·소켓 타임아웃)와 잘못된/빈 응답을 모두 닫힌 어휘 {@link InfraManagerClient.CallFailedException}으로만
 * 변환한다. raw 전송 예외를 밖으로 내보내면 상위(StepRunner)가 {@code CallTimeout}/{@code CallFailed}만 catch하므로
 * fail-fast로 오인 전파된다. 반대로 {@code RuntimeException}을 통째로 잡지는 않는다 — 매핑 로직 자체의 버그는
 * 그대로 전파되어야 한다(fail-fast). 호출별 타임아웃과 인터럽트는 이 delegate가 아니라 데코레이터가 소유한다.
 *
 * <p><b>terraform dispatch 응답.</b> InfraManager는 {@code {"jobIds":[...]}} 객체를 주지만, 도메인의
 * {@code TerraformTask}는 {@code task_attempt.response}를 bare {@code List<String>} JSON으로 역직렬화한다. 따라서
 * 여기서 {@code jobIds}만 {@code ["job-1", ...]} 문자열로 재직렬화해 넘긴다.
 */
public class InfraManagerFeignAdapter implements InfraManagerClient {

    private final InfraManagerFeignClient feign;
    private final ObjectMapper objectMapper;

    public InfraManagerFeignAdapter(InfraManagerFeignClient feign, ObjectMapper objectMapper) {
        this.feign = feign;
        this.objectMapper = objectMapper;
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        TerraformDispatchResponse response = translating(() -> feign.runTerraform(operation, target));
        List<String> jobIds = response == null ? null : response.jobIds();
        if (jobIds == null || jobIds.isEmpty()) {
            throw new CallFailedException("InfraManager returned no job ids for " + operation);
        }
        return serializeJobIds(jobIds);
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId) {
        TerraformStatusResponse response = translating(() -> feign.terraformJobStatus(jobId));
        if (response == null) {
            throw new CallFailedException("InfraManager returned no status for job " + jobId);
        }
        return new TerraformPoll(response.finished(), response.succeeded());
    }

    @Override
    public boolean checkCondition(String target, TaskOperation operation) {
        ConditionResponse response = translating(() -> feign.checkCondition(operation, target));
        if (response == null) {
            throw new CallFailedException("InfraManager returned no condition result for " + operation);
        }
        return response.met();
    }

    @Override
    public CloudProvider cloudProvider(String target) {
        CloudProviderResponse response = translating(() -> feign.cloudProvider(target));
        String provider = response == null ? null : response.provider();
        try {
            return CloudProvider.valueOf(provider);
        } catch (IllegalArgumentException | NullPointerException unusable) {
            throw new CallFailedException("InfraManager returned an unusable cloud provider '" + provider
                    + "' for target " + target);
        }
    }

    private String serializeJobIds(List<String> jobIds) {
        try {
            return objectMapper.writeValueAsString(jobIds);
        } catch (JsonProcessingException impossible) {
            // List<String> 직렬화는 실패할 수 없다; 방어적으로 닫힌 어휘로 감싼다.
            throw new CallFailedException("failed to serialize job ids: " + impossible.getOriginalMessage());
        }
    }

    /**
     * Feign 전송 예외만 {@link CallFailedException}으로 변환한다. {@link feign.RetryableException}(연결 거부·소켓 타임아웃)은
     * {@link FeignException}의 하위 타입이라 이 한 catch로 HTTP 4xx/5xx까지 함께 잡힌다. 그 외 {@code RuntimeException}은
     * 잡지 않고 전파한다(fail-fast).
     */
    private <T> T translating(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeignException transportFailure) {
            throw new CallFailedException("infra-manager call failed: " + transportFailure.getMessage());
        }
    }
}
