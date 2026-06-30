package com.bff.pipeline.model;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;

/**
 * task의 종류와 그 실행 방식을 정의하는 인터페이스이다. 엔진은 각 task의 {@link #taskName()}을
 * 행에 저장하고, {@code TaskTypeRegistry}를 통해 일치하는 {@code TaskType}을 해석하여 구동한다 —
 * 따라서 새로운 종류의 task는 자신을 등록하는 새 구현체를 추가하는 것이지, {@code switch}를
 * 편집하는 것이 아니다(ADR-016 §2 확장 심(extension seam)). 엔진 내의 진정한 다형성 경계가
 * 바로 이 인터페이스이며, 각 구현체가 실질적으로 구별되는 동작을 갖기 때문에 인터페이스로 정의한다
 * (단일 구현 간접 참조가 아니다).
 *
 * <p>{@code TaskType}은 하나의 task를 <em>시작</em>하고 <em>폴링</em>하는 방법만 안다. 엔진이
 * 주변 상태 기계(state machine), 재시도/실패 결정, 관찰 기록, 그리고 {@code InfraManagerClient}
 * 호출 예외를 영속화된 {@code ErrorCode}로 변환하는 책임을 맡는다.
 *
 * <p>{@code taskName()}은 이 타입의 모든 task에 영속화되는 안정적인 이름이며, registry가 task
 * 행을 해석하는 데 사용한다. {@code execute(target, task)}는 외부 작업을 멱등하게 시작하고(ADR-016 §5)
 * <em>원시 dispatch response</em>를 {@link DispatchResult}로 반환하며, 엔진이 이를 {@code task_attempt.response}에 기록한다
 * (순수 폴링 타입은 {@link DispatchResult#NONE} 반환). <em>호출 실패</em>만 {@code RuntimeException}을 던져 신호한다.
 * 엔진은 {@code CallTimeoutException}을 CALL_TIMEOUT으로, {@code CallFailedException}(null/blank 응답 가드 포함)을
 * CHECK_ERROR로 매핑하며, 그 외 순수 {@code RuntimeException}(진짜 버그)과 {@code CallInterruptedException}은
 * 캐치하지 않고 그대로 전파한다(fail-fast). <em>비즈니스</em> 실패는 절대 예외로 신호하지 않는다.
 * {@code check(target, task, attempt)}는 최신 {@code attempt}의 {@code response}를 역직렬화해 진행 상태를 판정하고,
 * 엔진이 다음에 할 행동을 {@link TaskProgress} 값으로 보고한다 — 완료, 보류, 또는 실패(retryable
 * 플래그 포함) — 비즈니스 실패는 여기서 데이터이며 절대 예외가 아니다
 * ({@code docs/exception-strategy.md} 참조).
 *
 * <p>라이프사이클 순서: {@code execute} → {@code check}. {@code check}가 성공을 반환하면 task는 곧장 DONE이다
 * (ADR-016 §3: 완료는 최신 attempt 위 코드 레벨 check 한 번으로 판정하며 별도의 사후 단계가 없다).
 */
public interface TaskType {

    String taskName();

    /**
     * 외부 작업을 멱등하게 시작하고(ADR-016 §5) dispatch 결과를 {@link DispatchResult}로 반환한다. 엔진은 이 값을 보고
     * {@code task_attempt.response}에 무엇을 기록할지 결정한다 — {@link DispatchResult.WithResponse}는 그 원시 텍스트를
     * (형식을 해석하지 않고) 그대로 저장하고, {@link DispatchResult#NONE}은 응답 없음(void)으로 기록하지 않는다.
     * 응답의 스키마는 전적으로 이 task type의 사적 계약이며, 엔진은 그 모양을 가정하지 않는다.
     * <em>호출 실패</em>만 {@code RuntimeException}으로 신호한다.
     */
    DispatchResult execute(String target, Task task);

    /**
     * 최신 {@code attempt}의 {@code response}를 자기 방식으로 역직렬화해 진행 상태를 한 번 판정한다
     * (ADR-016 §3 invariant 1: 완료는 최신 attempt 결과 위의 코드 레벨 check). 비즈니스 결과는 {@link TaskProgress}
     * 값이며 절대 예외가 아니다. 호출 실패만 {@code RuntimeException}으로 신호한다.
     */
    TaskProgress check(String target, Task task, TaskAttempt attempt);
}
