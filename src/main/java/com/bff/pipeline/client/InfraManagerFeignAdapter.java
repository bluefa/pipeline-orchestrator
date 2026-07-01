package com.bff.pipeline.client;

import com.bff.pipeline.dto.ApplyJobStatusResponse;
import com.bff.pipeline.dto.DestroyJobStatusResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link InfraManagerFeignClient}(raw HTTP)를 감싸 도메인이 요구하는 {@link InfraManagerClient} 계약으로 변환하는
 * 프로덕션 delegate다. {@code FeignConfig}가 이 인스턴스를 {@code infraManagerDelegate} 빈으로 등록하면, 기존
 * {@link TimeBoundedInfraManagerClient} 데코레이터가 활성화되어 도메인은 데코레이터를 주입받는다.
 *
 * <p><b>operation별 API·응답 라우팅(delegate의 핵심 책임).</b> operation마다 InfraManager의 실제 API 경로도, 응답 형태도
 * 다르다. 그래서 각 도메인 호출({@code runTerraform}/{@code terraformJobStatus}/{@code checkCondition})은 operation을
 * {@code switch}로 라우팅해 그 operation 전용 Feign 메서드를 부르고, operation별 응답 DTO를 도메인 공통 형식(terraform
 * dispatch → bare job id 배열 문자열, status → {@link TerraformPoll}, condition → boolean)으로 정규화한다. operation이
 * 늘면 여기에 case·전용 Feign 메서드·응답 DTO를 함께 추가한다 — operation 하나가 실제로 다른 API 하나이기 때문이다.
 * 매핑이 없는 operation은 설정 버그이므로 {@link IllegalStateException}으로 fail-fast한다.
 *
 * <p><b>예외 경계.</b> Feign 전송 실패({@link FeignException} — HTTP 4xx/5xx, {@link feign.RetryableException} — 연결
 * 거부·소켓 타임아웃)와 잘못된/빈 응답을 모두 닫힌 어휘 {@link InfraManagerClient.CallFailedException}으로만 변환한다.
 * raw 전송 예외를 밖으로 내보내면 상위(StepRunner)가 {@code CallTimeout}/{@code CallFailed}만 catch하므로 fail-fast로
 * 오인 전파된다. 반대로 {@code RuntimeException}을 통째로 잡지는 않는다 — 매핑 로직 자체의 버그는 그대로 전파되어야
 * 한다(fail-fast). 호출별 타임아웃과 인터럽트는 이 delegate가 아니라 데코레이터가 소유한다.
 *
 * <p><b>terraform dispatch 응답.</b> 도메인의 {@code TerraformTask}는 {@code task_attempt.response}를 bare
 * {@code List<String>} JSON으로 역직렬화한다. 따라서 operation별 응답에서 뽑은 {@code jobIds}만 {@code ["job-1", ...]}
 * 문자열로 재직렬화해 넘긴다.
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
        List<String> jobIds = switch (operation) {
            case APPLY_NETWORK -> translating(() -> feign.applyNetwork(target)).jobIds();
            case DESTROY_NETWORK -> translating(() -> feign.destroyNetwork(target)).jobIds();
            default -> throw new IllegalStateException("no terraform dispatch API mapped for operation " + operation);
        };
        if (jobIds == null || jobIds.isEmpty()) {
            throw new CallFailedException("InfraManager returned no job ids for " + operation);
        }
        return serializeJobIds(jobIds);
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) {
        return switch (operation) {
            case APPLY_NETWORK -> poll(translating(() -> feign.applyJobStatus(jobId)),
                    ApplyJobStatusResponse::finished, ApplyJobStatusResponse::succeeded, jobId);
            case DESTROY_NETWORK -> poll(translating(() -> feign.destroyJobStatus(jobId)),
                    DestroyJobStatusResponse::finished, DestroyJobStatusResponse::succeeded, jobId);
            default -> throw new IllegalStateException("no terraform status API mapped for operation " + operation);
        };
    }

    /** operation별 status 응답을 도메인 {@link TerraformPoll}로 정규화한다. 응답 null·누락 필드·불가능 조합은 모두 CallFailed. */
    private <S> TerraformPoll poll(S status, Function<S, Boolean> finished, Function<S, Boolean> succeeded, String jobId) {
        if (status == null) {
            throw new CallFailedException("InfraManager returned no status for job " + jobId);
        }
        return toPoll(finished.apply(status), succeeded.apply(status), jobId);
    }

    @Override
    public boolean checkCondition(String target, TaskOperation operation) {
        Boolean met = switch (operation) {
            case NETWORK_READY -> translating(() -> feign.networkReady(target)).met();
            default -> throw new IllegalStateException("no condition-check API mapped for operation " + operation);
        };
        if (met == null) {
            throw new CallFailedException("InfraManager returned no condition result for " + operation);
        }
        return met;
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

    /** operation별 status 응답(nullable Boolean 2개)을 도메인 {@link TerraformPoll}로 정규화한다. 누락·불가능 조합은 CallFailed. */
    private TerraformPoll toPoll(Boolean finished, Boolean succeeded, String jobId) {
        if (finished == null || succeeded == null) {
            throw new CallFailedException("InfraManager returned an incomplete status for job " + jobId);
        }
        try {
            return new TerraformPoll(finished, succeeded);
        } catch (IllegalArgumentException impossibleState) {
            // 불가능한 조합(!finished && succeeded)은 쓸 수 없는 외부 응답이지 우리 버그가 아니다 → 닫힌 어휘로.
            throw new CallFailedException("InfraManager returned an impossible poll state for job " + jobId);
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
