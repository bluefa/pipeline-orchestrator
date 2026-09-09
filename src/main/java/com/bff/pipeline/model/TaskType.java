package com.bff.pipeline.model;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;

/**
 * 각 작업의 실행·완료 판정 방법을 정의하는 다형성 경계다. 엔진은 저장된 이름으로 구현을 선택한다. 외부 호출은
 * 상태 기록 트랜잭션 밖에서 수행하며 결과 값만 기록 단계로 전달한다. Terraform은 현재 attempt 응답을 읽고,
 * 추천 기반 입력은 Task별 불변 입력을 읽는다. 관찰 attempt 유실의 복구 규칙도 작업 종류가 결정한다.
 *
 * 알려진 업무 실패는 값으로 표현한다. 통제된 외부 오류는 StepRunner가 한 번 번역한 뒤 handleCallFailure로
 * 종류별 복구 정책을 적용한다. 인터럽트와 예상하지 못한 코드 오류는 삼키지 않는다.
 */
public interface TaskType {

    String taskName();

    default StepOutcome handleCallFailure(Task task, StepOutcome.CallFailure failure) { return failure; }

    /**
     * 외부 작업을 멱등하게 시작하고(ADR-016 §5) dispatch 결과를 {@link DispatchResult}로 돌려준다. 엔진은 이 값을 보고
     * {@code task_attempt.response}에 무엇을 기록할지 정한다 — {@link DispatchResult.WithResponse}는 원시 텍스트를
     * 형식 해석 없이 그대로 저장하고, {@link DispatchResult#NONE}은 응답 없음(void)이라 기록하지 않는다.
     * 응답 스키마는 전적으로 이 task type의 사적 계약이며, 엔진은 그 모양을 가정하지 않는다.
     * <em>호출 실패</em>만 {@code RuntimeException}으로 알린다.
     */
    DispatchResult execute(String target, Task task);

    /**
     * 최신 {@code attempt}의 {@code response}를 자기 방식으로 역직렬화해 진행 상태를 한 번 판정한다
     * (ADR-016 §3 invariant 1: 완료는 최신 attempt 결과 위의 코드 레벨 check). {@code attempt}는 항상
     * non-null이다 — 유실된 경우 엔진은 이 메서드 대신 {@link #checkWithoutAttempt}를 부른다. 비즈니스 결과는
     * {@link TaskProgress} 값이지 예외가 아니다. 호출 실패만 {@code RuntimeException}으로 알린다.
     */
    TaskProgress check(String target, Task task, TaskAttempt attempt);

    /**
     * IN_PROGRESS인데 현재 attempt 관찰 행이 없을 때(관찰 유실 — dispatch 후 기록 유실이나 수동 개입)의
     * 타입별 복구 정책이다. 엔진({@code StepRunner})이 null attempt를 이 메서드로 분기하므로 {@link #check}는
     * attempt를 항상 non-null로 받는다. 결과 규약은 {@link #check}와 같다 — 비즈니스 결과는 {@link TaskProgress}
     * 값이지 예외가 아니고, 호출 실패만 {@code RuntimeException}으로 알린다.
     */
    TaskProgress checkWithoutAttempt(String target, Task task);
}
