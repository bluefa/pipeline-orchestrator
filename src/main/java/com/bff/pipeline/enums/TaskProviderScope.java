package com.bff.pipeline.enums;

/** Task 정의를 특정 CSP에서만 구성할지 모든 CSP에서 공통으로 구성할지 구분한다. */
public enum TaskProviderScope {
    /** 정의에 지정한 CSP에서만 선택할 수 있다. */
    CSP_SPECIFIC,
    /** 모든 CSP에서 공통 정의를 선택하고 실제 호출은 파이프라인 CSP로 라우팅한다. */
    ALL_CSP
}
