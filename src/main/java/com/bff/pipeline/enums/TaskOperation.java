package com.bff.pipeline.enums;

/**
 * 태스크가 수행하는 도메인 액션을 나타내는 열거형으로, ADR의 조건부 여섯 번째 열거형이다.
 * v1 작업 집합이 <em>폐쇄형(closed)</em>이므로 일급 타입으로 유지된다(ADR-016 §2).
 * 태스크의 유형({@code taskName})은 dispatch 및 poll 방식을 결정하고,
 * operation은 그 안에서의 세부 액션을 선택한다.
 *
 * <p>작업 집합이 이후 개방형/구성형으로 전환될 경우, 이 열거형은 레지스트리 패턴으로 대체된다
 * ({@code docs/extensibility.md} 참조). 그때까지는 폐쇄형 열거형이 타입 안전성을 보장한다.
 */
public enum TaskOperation {
    /** 네트워크 인프라를 구성(apply)하는 액션. */
    APPLY_NETWORK,
    /** 네트워크가 준비 상태인지 확인(condition check)하는 액션. */
    NETWORK_READY,
    /** 네트워크 인프라를 철거(destroy)하는 액션. */
    DESTROY_NETWORK
}
