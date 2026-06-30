package com.bff.pipeline.service.task.type;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.model.TaskProgress;

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
 * 행을 해석하는 데 사용한다. {@code execute(target, task)}는 외부 작업을 멱등하게 시작하며
 * (ADR-016 §5) — 예: 잡을 제출하고 핸들을 task에 기록 — 순수 폴링 타입에서는 no-op일 수 있다.
 * <em>호출 실패</em>만 {@code RuntimeException}을 던져 신호한다. 엔진은 {@code CallTimeoutException}을
 * CALL_TIMEOUT으로, {@code CallFailedException}(null/blank job id 가드 포함)을 CHECK_ERROR로 매핑하며,
 * 그 외 순수 {@code RuntimeException}(진짜 버그)과 {@code CallInterruptedException}은 캐치하지 않고
 * 그대로 전파한다(fail-fast). <em>비즈니스</em> 실패는
 * 절대 예외로 신호하지 않는다. {@code check(target, task)}는 진행 상태를 한 번 폴링하고,
 * 엔진이 다음에 할 행동을 {@link TaskProgress} 값으로 보고한다 — 완료, 보류, 또는 실패(retryable
 * 플래그 포함) — 비즈니스 실패는 여기서 데이터이며 절대 예외가 아니다
 * ({@code docs/exception-strategy.md} 참조).
 *
 * <p>라이프사이클 순서: {@code execute} → {@code check} → {@code postCheck}.
 * {@code postCheck}는 {@code check}가 성공을 반환한 직후에 호출되는 사후 검증 단계다.
 */
public interface TaskType {

    String taskName();

    void execute(String target, Task task);

    TaskProgress check(String target, Task task);

    /** check 성공 이후의 사후 검증 단계. 기본은 검증 없음({@code SUCCEEDED}); 필요한 task type만 재정의한다. */
    default TaskProgress postCheck(String target, Task task) { return TaskProgress.SUCCEEDED; }
}
