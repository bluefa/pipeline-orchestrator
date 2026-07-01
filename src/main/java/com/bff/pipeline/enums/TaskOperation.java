package com.bff.pipeline.enums;

/**
 * 태스크가 수행하는 도메인 액션을 담는 열거형으로, ADR의 조건부 여섯 번째 열거형이다. v1 작업 집합이
 * <em>폐쇄형(closed)</em>이라 일급 타입으로 남겨 둔다(ADR-016 §2). 태스크 유형({@code taskName})이
 * dispatch/poll 방식을 결정하고, operation은 그 안에서 세부 액션을 고른다.
 *
 * <p>나중에 작업 집합이 개방형/구성형으로 바뀌면 이 열거형은 레지스트리 패턴으로 대체된다
 * ({@code docs/extensibility.md} 참조). 그전까지는 폐쇄형 열거형이 타입 안전성을 지켜 준다.
 */
public enum TaskOperation {
    /** 네트워크 인프라를 구성(apply)하는 액션. */
    APPLY_NETWORK,
    /** 네트워크가 준비됐는지 확인(condition check)하는 액션. */
    NETWORK_READY,
    /** 네트워크 인프라를 철거(destroy)하는 액션. */
    DESTROY_NETWORK
}
