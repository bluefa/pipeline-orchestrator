package com.bff.pipeline.client.terraform;

import com.bff.pipeline.dto.TerraformJobStatusResponse;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import java.util.List;
import java.util.Set;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 한 TERRAFORM_JOB operation의 InfraManager API 바인딩이다 — 그 operation의 dispatch/status/result 실제 호출과
 * 응답 → 도메인 공통 형식 변환을 한 곳에 응집한다. operation마다 바인딩 빈 하나가 있고(카탈로그
 * {@code TerraformBindingCatalog}의 행), {@link com.bff.pipeline.client.InfraManagerOperationRegistry}가 부팅 시
 * 모든 TERRAFORM_JOB operation이 정확히 하나의 바인딩을 갖는지 검증한다. 새 operation을 추가하는 개발자는
 * 카탈로그에 행을 추가하면 되고, 빠뜨리면 registry 검증이 부팅/CI에서 실패한다.
 *
 * Feign 전송 예외(FeignException)는 여기서 잡지 않는다 — {@code InfraManagerFeignAdapter}의 단일 경계가 닫힌
 * 어휘로 변환한다. 이 바인딩은 응답 방어(null·누락·불가능 조합)만 {@link CallFailedException}으로 닫는다.
 */
public interface TerraformOperationBinding {

    TaskOperation operation();

    /** operation 전용 dispatch API를 호출해 job id 목록을 얻는다. */
    List<String> dispatchJobIds(String target);

    /** operation 전용 status API를 호출해 job 하나의 완료 상태를 얻는다. */
    TerraformPoll poll(String jobId);

    /** operation 전용 result API를 호출해 job 하나의 result(= terraform log)를 얻는다 — postCheck 관찰(확장 A). */
    String result(String jobId);

    /**
     * dispatch 응답의 job id 목록 방어 — 목록이 null·비었거나 요소에 null·blank가 섞였으면 쓸 수 없는 외부 응답이므로
     * {@link CallFailedException}으로 닫는다(→ StepRunner 재시도 경계). 여기서 막지 않으면 {@code [""]} 같은 응답이
     * 성공 dispatch로 저장됐다가 poll 단계에서 terminal 실패로 바뀌어 재시도 기회를 잃는다.
     */
    static List<String> requireJobIds(List<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty() || jobIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new CallFailedException("InfraManager returned no usable job ids");
        }
        if (Set.copyOf(jobIds).size() != jobIds.size()) {
            // 같은 job id가 두 번 오는 응답도 쓸 수 없다 — job당 1행/1폴을 전제하는 집계·관찰 계약이 깨진다.
            throw new CallFailedException("InfraManager returned duplicate job ids");
        }
        return jobIds;
    }

    /**
     * status 응답의 {@code terraformState}(String — 전체 값 목록 미확정, {@link TerraformJobType} TODO 참조)를 도메인
     * {@link TerraformPoll}로 정규화한다. terminal 값만 해석한다: {@code FAILED} → 실패, jobType의 성공 상태
     * 목록에 드는 값 → 성공, 그 외 전부(미지 포함) → 진행 중. 미지 상태를 호출 실패로
     * 닫지 않는 이유(owner 지침): 전이 중 상태는 다음 폴이 해소하고, 영영 안 끝나면 executionTimeout이 회수한다 —
     * 무한 대기가 아니라 기존 예산 안의 지연이다. 응답이 없거나 상태가 비면 해석 불능이므로 CallFailed로 닫는다.
     */
    static TerraformPoll toPoll(TerraformJobStatusResponse status, TerraformJobType jobType, String jobId) {
        if (status == null || status.terraformState() == null || status.terraformState().isBlank()) {
            throw new CallFailedException("InfraManager returned an incomplete status for job " + jobId);
        }
        String state = status.terraformState();
        // 판정은 terminal 세 값만 해석하지만, 관찰용 원문(response)은 어느 상태든 그대로 실어 진행-시점 가시성을 남긴다.
        return normalize(state, jobType, status.failReason()).withResponse(status.raw());
    }

    private static TerraformPoll normalize(String state, TerraformJobType jobType, String failReason) {
        if (TerraformJobType.FAILED_STATE.equals(state)) {
            return TerraformPoll.failure(state, failReason);
        }
        if (jobType.isSuccess(state)) {
            return TerraformPoll.success(state);
        }
        return TerraformPoll.running(state);
    }

    /** result 응답 방어 — null 본문은 쓸 수 없는 외부 응답이므로 CallFailed로 닫는다(빈 문자열은 유효한 빈 로그). */
    static String requireResult(String result, String jobId) {
        if (result == null) {
            throw new CallFailedException("InfraManager returned no result for job " + jobId);
        }
        return result;
    }
}
