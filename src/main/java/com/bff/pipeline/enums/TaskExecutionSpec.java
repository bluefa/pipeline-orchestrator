package com.bff.pipeline.enums;

import com.bff.pipeline.client.terraform.TerraformJobType;

/**
 * TaskDefinition 한 항목의 실행 계약을 운영자 언어로 못박은 metadata다 — 실제로 어떤 API를 호출하고,
 * 어떤 API를 어떤 정책으로 폴링해 성공을 판정하며, 종결 후 result가 어디에 어떻게 저장되는지를 담아
 * Admin API로 그대로 노출된다. API 문자열은 "HTTP메서드 경로(?고정쿼리)" 표기이며, 확정된 InfraManager 실 명세
 * (docs/terraform-client-and-postcheck-design.md §1)와 1:1이다 — 실행 라우팅의 진실원은 여전히
 * InfraManagerFeignClient/TerraformBindingCatalog이고 이 문자열은 표시용 사본이다.
 *
 * timeout·폴링 간격·재시도 횟수는 여기 두지 않는다 — 값의 진실원은 task 행 오버라이드 + 전역
 * PipelineSettings이고(TaskSettingsResolver), Admin 응답에서는 TaskDetail의 effective_* 필드가 실제 적용값을
 * 보여준다. 정책 텍스트는 그 필드 이름(polling_interval, execution_timeout, max_fail_count)을 언급해
 * 운영자가 값과 정책을 잇게 한다.
 *
 * 정책 텍스트는 항목마다 손으로 쓰지 않고 mechanism과 job 타입에서 생성한다({@link #terraform}/{@link #conditionCheck})
 * — 성공 terminal 상태의 authority는 {@link TerraformJobType}이므로 여기서 문구만 조립한다.
 */
public record TaskExecutionSpec(
        String dispatchApi,
        String statusApi,
        String resultApi,
        String successPolicy,
        String resultStorage) {

    /** TERRAFORM_JOB 항목의 스펙 — 성공 판정·저장 정책 텍스트를 job 타입에서 생성한다. */
    public static TaskExecutionSpec terraform(TerraformJobType jobType, String dispatchApi, String statusApi,
            String resultApi) {
        String successPolicy = "디스패치가 만든 모든 job을 polling_interval 간격으로 상태 API로 폴링한다. "
                + "모든 job의 terraformState가 " + jobType.successState() + "이면 성공이다. 하나라도 "
                + TerraformJobType.FAILED_STATE + "이면 나머지 job이 종결될 때까지 기다린 뒤 JOB_FAILED로 판정하고, "
                + "attempt 시작 후 execution_timeout 안에 전부 종결되지 않으면 EXECUTION_TIMEOUT으로 판정한다. "
                + "두 판정과 호출 오류는 fail_count로 누적되며, max_fail_count에 도달할 때까지 멱등 재디스패치로 "
                + "재시도한다.";
        String resultStorage = "판정이 내려지는 turn에 종결된 job마다 result API로 terraform log를 조회해 "
                + "terraform_result 테이블에 job당 1행으로 저장한다. 저장 상한을 넘는 본문은 앞부분을 절단하고 "
                + "truncated로 표시하며, 조회에 실패한 job은 본문 없이 result_path만 남긴다. 이 저장은 관찰 전용이라 "
                + "태스크 판정에는 영향을 주지 않는다.";
        return new TaskExecutionSpec(dispatchApi, statusApi, resultApi, successPolicy, resultStorage);
    }

    /** CONDITION_CHECK 항목의 스펙 — dispatch와 result가 없고, 실행 타임아웃 대신 재시도 예산으로 경계된다. */
    public static TaskExecutionSpec conditionCheck(String checkApi) {
        String successPolicy = "디스패치 없이 조건 확인 API를 polling_interval 간격으로 호출한다. 조건 충족이 "
                + "관측되면 성공이고, 미충족과 호출 오류는 fail_count로 누적돼 max_fail_count에 도달하면 실패한다"
                + "(execution_timeout 대신 재시도 예산으로 경계된다).";
        String resultStorage = "별도 result 저장은 없다 — 폴 관찰(호출 횟수, 마지막 외부 상태)은 task_check에 남는다.";
        return new TaskExecutionSpec(null, checkApi, null, successPolicy, resultStorage);
    }
}
