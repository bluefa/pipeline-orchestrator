package com.bff.pipeline.client;

import com.bff.pipeline.enums.TaskOperation;

/**
 * 한 CONDITION_CHECK operation의 InfraManager API 바인딩이다 — 그 operation의 조건 탐색 실제 호출과 응답 변환을 응집한다.
 * operation마다 하나의 {@code @Component} 구현체를 두고, {@link InfraManagerOperationRegistry}가 부팅 시 모든
 * CONDITION_CHECK operation이 정확히 하나의 바인딩을 갖는지 검증한다. (전송 예외는 어댑터가, 응답 방어는 여기서.)
 */
public interface ConditionOperationBinding {

    TaskOperation operation();

    /** operation 전용 조건 탐색 API를 호출해 충족 여부를 얻는다. */
    boolean check(String target);

    /** 조건 응답 방어 — null이면 쓸 수 없는 외부 응답이므로 CallFailed. */
    static boolean requireMet(Boolean met, TaskOperation operation) {
        if (met == null) {
            throw new InfraManagerClient.CallFailedException("InfraManager returned no condition result for " + operation);
        }
        return met;
    }
}
