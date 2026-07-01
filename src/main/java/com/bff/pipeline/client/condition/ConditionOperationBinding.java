package com.bff.pipeline.client.condition;

import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 한 CONDITION_CHECK operation의 InfraManager API 바인딩이다 — 그 operation의 조건 탐색 실제 호출과 응답 변환을 응집한다.
 * operation마다 하나의 {@code @Component} 구현체를 두고, {@link InfraManagerOperationRegistry}가 부팅 시 모든
 * CONDITION_CHECK operation이 정확히 하나의 바인딩을 갖는지 검증한다. (전송 예외는 어댑터가, 응답 방어는 여기서.)
 */
public interface ConditionOperationBinding {

    TaskOperation operation();

    /** operation 전용 조건 탐색 API를 호출해 충족 여부와 원시 payload를 함께 얻는다. */
    ConditionPoll check(String target);

    /**
     * 조건 응답을 방어하고 {@link ConditionPoll}로 만든다 — {@code met}이 null이면 쓸 수 없는 외부 응답이므로
     * CallFailed로 닫고, 그렇지 않으면 원시 응답을 직렬화해 payload로 싣는다({@code task_attempt.response}용).
     */
    static ConditionPoll poll(Boolean met, Object rawResponse, ObjectMapper objectMapper, TaskOperation operation) {
        if (met == null) {
            throw new CallFailedException("InfraManager returned no condition result for " + operation);
        }
        try {
            return new ConditionPoll(met, objectMapper.writeValueAsString(rawResponse));
        } catch (JsonProcessingException impossible) {
            throw new CallFailedException("failed to serialize condition response for " + operation + ": "
                    + impossible.getOriginalMessage());
        }
    }
}
