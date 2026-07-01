package com.bff.pipeline.client.terraform;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 한 TERRAFORM_JOB operation의 InfraManager API 바인딩이다 — 그 operation의 dispatch/status 실제 호출과 operation별
 * 응답 DTO → 도메인 공통 형식 변환을 <b>한 곳에</b> 응집한다. operation마다 하나의 {@code @Component} 구현체를 두고,
 * {@link InfraManagerOperationRegistry}가 부팅 시 모든 TERRAFORM_JOB operation이 정확히 하나의 바인딩을 갖는지 검증한다.
 * 새 terraform operation을 추가하는 개발자는 이 인터페이스를 구현하면 컴파일러가 무엇을(dispatch/status) 짜야 하는지
 * 강제하고, 빠뜨리면 registry 검증이 부팅/CI에서 실패한다.
 *
 * <p>Feign 전송 예외(FeignException)는 여기서 잡지 않는다 — {@code InfraManagerFeignAdapter}의 단일 경계가 닫힌 어휘로
 * 변환한다. 이 바인딩은 operation별 <em>응답 방어</em>(null·누락·불가능 조합)만 {@link CallFailedException}으로 닫는다.
 */
public interface TerraformOperationBinding {

    TaskOperation operation();

    /** operation 전용 dispatch API를 호출해 job id 목록을 얻는다. */
    List<String> dispatchJobIds(String target);

    /** operation 전용 status API를 호출해 job 하나의 완료 상태를 얻는다. */
    TerraformPoll poll(String jobId);

    /**
     * dispatch 응답의 job id 목록 방어 — 목록이 null·비었거나 요소에 null·blank가 섞였으면 쓸 수 없는 외부 응답이므로
     * {@link CallFailedException}으로 닫는다(→ StepRunner 재시도 경계). 여기서 막지 않으면 {@code [""]} 같은 응답이
     * 성공 dispatch로 저장됐다가 poll 단계에서 terminal 실패로 바뀌어 재시도 기회를 잃는다.
     */
    static List<String> requireJobIds(List<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty() || jobIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new CallFailedException("InfraManager returned no usable job ids");
        }
        return jobIds;
    }

    /** operation별 status 필드(nullable Boolean)를 도메인 {@link TerraformPoll}로 정규화. null·누락·불가능 조합은 CallFailed. */
    static TerraformPoll toPoll(Boolean finished, Boolean succeeded, String jobId) {
        if (finished == null || succeeded == null) {
            throw new CallFailedException("InfraManager returned an incomplete status for job " + jobId);
        }
        try {
            return new TerraformPoll(finished, succeeded);
        } catch (IllegalArgumentException impossibleState) {
            throw new CallFailedException("InfraManager returned an impossible poll state for job " + jobId);
        }
    }
}
