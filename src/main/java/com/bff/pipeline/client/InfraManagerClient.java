package com.bff.pipeline.client;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;

/**
 * InfraManager 경계(boundary) 인터페이스로, 이 모듈에서 유일한 실제 외부 연동 인터페이스이다.
 * 프로덕션 구현체는 HTTP로 동작하며, 테스트에서는 페이크(fake)로 대체된다.
 * 모든 메서드는 짧고 동기적인 호출로, 핸들 또는 상태를 반환하며 잡의 최종 결과를 직접 반환하지 않는다.
 *
 * <p><b>실패는 아래 세 가지 예외로만 전달된다.</b> 호출 실패는 오직 아래에 정의된 중첩 예외 중
 * 하나로만 신호화된다 — 폐쇄된 어휘(closed vocabulary)이므로, 도메인은 외부 호출 실패만 정확히
 * 캐치하고 그 외의 {@code RuntimeException}(진짜 버그)은 전파되어 빠르게 실패하게 된다.
 * 프로덕션 어댑터(ADR-021 러너가 호출별 타임아웃을 소유)는 타임아웃 시 {@link CallTimeoutException}을,
 * 인터럽트 시 {@link CallInterruptedException}을, 그 외 실패한 호출(HTTP 오류, 거부,
 * 잘못된/빈 응답)에는 {@link CallFailedException}을 발생시켜야 한다. 프로덕션 어댑터는
 * 자신의 전송 예외를 반드시 이 세 가지 중 하나로 변환해야 하며, 날 것의 {@code RuntimeException}을
 * 누출해서는 안 된다. 도메인({@code TaskMachine})은 이 예외들을 캐치하여 영속된 {@code ErrorCode}로
 * 변환하는 단일 경계이다 — CallTimeout → CALL_TIMEOUT, CallFailed → CHECK_ERROR.
 * CallInterrupted는 캐치하지 않고 그대로 전파되어 fail-fast로 처리된다. 자세한 내용은
 * {@code docs/exception-strategy.md} 참조.
 *
 * <p>모든 dispatch는 <b>멱등성(idempotent)</b>을 보장한다(ADR-016 §5): 중복 submit은 인프라를
 * 올바른 상태로 유지하므로, 최소 한 번(at-least-once) 재dispatch가 안전하다.
 */
public interface InfraManagerClient {

    /** operation에 대한 Terraform 잡을 디스패치하고 job id를 반환한다. */
    String runTerraform(String target, TaskOperation operation);

    /** job id 핸들로 Terraform 잡 상태를 읽는다. */
    TerraformPoll terraformJobStatus(String jobId);

    /** operation의 조건이 충족되었는지 한 번 탐색한다. */
    boolean checkCondition(String target, TaskOperation operation);

    /** 단일 InfraManager 호출이 호출별 타임아웃을 초과한 경우 발생한다(→ {@code ErrorCode.CALL_TIMEOUT}). */
    final class CallTimeoutException extends RuntimeException {
        public CallTimeoutException() {
            super("InfraManager call exceeded the per-call timeout");
        }
    }

    /** 호출 스레드가 인터럽트된 경우(예: 종료 신호) 발생한다 — 비즈니스 결과가 아닌 fail-fast 런타임 신호이다. */
    final class CallInterruptedException extends RuntimeException {
        public CallInterruptedException() {
            super("InfraManager call interrupted");
        }
    }

    /** 그 외 모든 InfraManager 호출 실패(HTTP 오류, 거부, 잘못된/빈 응답)에 대해 발생한다 → CHECK_ERROR. */
    final class CallFailedException extends RuntimeException {
        public CallFailedException(String message) { super(message); }
    }
}
