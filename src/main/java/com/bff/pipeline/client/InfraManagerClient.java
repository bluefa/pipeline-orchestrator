package com.bff.pipeline.client;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.exception.CallInterruptedException;
import com.bff.pipeline.exception.CallTimeoutException;

/**
 * InfraManager 경계 인터페이스 — 이 모듈이 실제 외부 시스템과 맞닿는 유일한 지점이다.
 * 프로덕션은 HTTP 구현으로, 테스트는 fake로 갈아끼운다. 메서드는 모두 짧은 동기 호출이라 핸들이나
 * 상태만 돌려주고, 잡의 최종 결과를 직접 반환하지는 않는다.
 *
 * <p><b>실패는 세 예외로만 전달한다.</b> 호출 실패의 신호는 {@code exception} 패키지의 닫힌 어휘 셋으로 닫혀 있다
 * (closed vocabulary): {@link CallTimeoutException}/{@link CallInterruptedException}/{@link CallFailedException}.
 * 덕분에 도메인은 외부 호출 실패만 골라 캐치하고, 그 밖의 {@code RuntimeException}(진짜 버그)은 그대로 전파돼
 * 빠르게 실패한다. 프로덕션 어댑터(호출별 타임아웃은 ADR-021 러너가 소유)는 타임아웃이면 {@code CallTimeoutException},
 * 인터럽트면 {@code CallInterruptedException}, 그 밖의 실패(HTTP 오류, 거부, 잘못된/빈 응답)면 {@code CallFailedException}을
 * 던진다. 자신의 전송 예외는 반드시 이 셋 중 하나로 변환해야 하고, 날 것의 {@code RuntimeException}을 새어 나가게 두면
 * 안 된다. 이 예외들을 영속되는 {@code ErrorCode}로 옮기는 단일 경계는 도메인({@code StepRunner})이다 —
 * CallTimeout → CALL_TIMEOUT, CallFailed → CHECK_ERROR. CallInterrupted는 캐치하지 않고 그대로 전파해 fail-fast로
 * 처리한다. 자세한 내용은 {@code docs/exception-strategy.md}를 참조한다.
 *
 * <p>모든 dispatch는 <b>멱등</b>하다(ADR-016 §5). 중복 submit도 인프라를 올바른 상태로 유지하므로
 * 최소 한 번(at-least-once) 재dispatch가 안전하다.
 */
public interface InfraManagerClient {

    /**
     * operation에 해당하는 Terraform 잡(들)을 디스패치하고 <b>원시 dispatch response</b>를 반환한다(ADR-016 ed97ec0 §5).
     * dispatch 한 번이 {@code N}개의 job id를 만들 수 있고, 그 집합이 이 응답에 담긴다. 엔진은 원시 응답을
     * {@code task_attempt.response}에 기록하고, {@code TerraformTask}가 이를 역직렬화해 각 job을 폴링한다.
     */
    String runTerraform(String target, TaskOperation operation);

    /**
     * job id 핸들로 Terraform 잡의 상태를 읽는다. 상태 조회 API 경로가 operation(apply/destroy)마다 다르므로
     * operation을 함께 받아 어느 구체 API로 갈지 결정한다.
     */
    TerraformPoll terraformJobStatus(String jobId, TaskOperation operation);

    /** operation의 조건이 충족됐는지 한 번 확인한다. */
    boolean checkCondition(String target, TaskOperation operation);

    /**
     * target(targetSourceId 기반)이 속한 cloud provider를 조회한다(설계 §3). create 시점에 한 번 불러 recipe를 고르고
     * Pipeline에 저장하므로 claim-pull 실행 경로는 이 호출에 의존하지 않는다. 조회 실패는 다른 호출과 같은 닫힌 어휘
     * 예외로 전달한다(장애/타임아웃 → 인프라 실패).
     */
    CloudProvider cloudProvider(String target);
}
