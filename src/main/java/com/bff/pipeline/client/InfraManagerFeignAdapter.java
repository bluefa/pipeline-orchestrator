package com.bff.pipeline.client;

import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.client.condition.ConditionOperationBinding;
import com.bff.pipeline.client.terraform.TerraformOperationBinding;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.List;
import java.util.function.Supplier;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 도메인이 요구하는 {@link InfraManagerClient} 계약의 프로덕션 delegate다. {@code FeignConfig}가 이 인스턴스를
 * {@code infraManagerDelegate} 빈으로 등록하면 기존 {@link TimeBoundedInfraManagerClient} 데코레이터가 활성화되어
 * 도메인은 데코레이터를 주입받는다.
 *
 * <p><b>operation 라우팅은 {@link InfraManagerOperationRegistry}가 소유한다.</b> operation마다 InfraManager 실제 API도
 * 응답도 다르므로, 각 operation의 호출·변환은 그 operation의 바인딩({@link TerraformOperationBinding}/
 * {@link ConditionOperationBinding})에 응집돼 있고 registry가 부팅 시 완전성을 검증한다. 이 어댑터는 도메인 메서드를
 * 해당 바인딩으로 위임하고, 공통 꼬리 작업(빈 job id 검사·직렬화)과 <b>전송 예외 번역</b>만 담당한다. operation을
 * switch하지 않는다 — 새 operation은 바인딩 하나 추가로 끝난다.
 *
 * <p><b>예외 경계.</b> Feign 전송 실패({@link FeignException} — HTTP 4xx/5xx, {@link feign.RetryableException} — 연결
 * 거부·소켓 타임아웃)를 닫힌 어휘 {@link CallFailedException}으로 변환한다({@link #translating}).
 * 바인딩이 던지는 응답 방어용 {@code CallFailedException}은 그대로 통과한다. {@code RuntimeException}을 통째로 잡지는
 * 않는다 — 매핑 로직 버그·바인딩 누락은 fail-fast로 전파돼야 한다. 호출별 타임아웃/인터럽트는 데코레이터가 소유한다.
 */
public class InfraManagerFeignAdapter implements InfraManagerClient {

    private final InfraManagerFeignClient feign;
    private final InfraManagerOperationRegistry registry;
    private final ObjectMapper objectMapper;

    public InfraManagerFeignAdapter(InfraManagerFeignClient feign, InfraManagerOperationRegistry registry,
            ObjectMapper objectMapper) {
        this.feign = feign;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        // 바인딩이 job id 목록 방어(null·empty·blank 요소 → CallFailed)를 소유한다(TerraformOperationBinding#requireJobIds).
        List<String> jobIds = translating(() -> registry.terraform(operation).dispatchJobIds(target));
        return serializeJobIds(jobIds);
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) {
        return translating(() -> registry.terraform(operation).poll(jobId));
    }

    @Override
    public String terraformJobResult(String jobId, TaskOperation operation) {
        return translating(() -> registry.terraform(operation).result(jobId));
    }

    @Override
    public ConditionPoll checkCondition(String target, TaskOperation operation) {
        return translating(() -> registry.condition(operation).check(target));
    }

    @Override
    public CloudProvider cloudProvider(String target) {
        var response = translating(() -> feign.cloudProvider(target));
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
     * {@link FeignException}의 하위 타입이라 이 한 catch로 HTTP 4xx/5xx까지 함께 잡힌다. 바인딩이 던지는 응답 방어용
     * {@code CallFailedException}과 그 밖의 {@code RuntimeException}(버그)은 잡지 않고 전파한다(fail-fast).
     */
    private <T> T translating(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeignException transportFailure) {
            throw new CallFailedException("infra-manager call failed: " + transportFailure.getMessage());
        }
    }
}
