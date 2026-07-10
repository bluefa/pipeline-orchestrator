package com.bff.pipeline.client.condition;

import com.bff.pipeline.dto.ConditionPoll;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.exception.CallFailedException;

/**
 * 한 CONDITION_CHECK operation의 InfraManager API 바인딩이다 — 그 operation의 조건 탐색 실제 호출과 응답 변환을 응집한다.
 * operation마다 하나의 {@code @Component} 구현체를 두고, {@link InfraManagerOperationRegistry}가 부팅 시 모든
 * CONDITION_CHECK operation이 정확히 하나의 바인딩을 갖는지 검증한다. (전송 예외는 어댑터가, 응답 방어는 여기서.)
 */
public interface ConditionOperationBinding {

    /**
     * 조건 응답 원문을 {@code task_attempt.response}에 싣기 전에 자르는 상한(문자 수). 그 컬럼은 MySQL {@code TEXT}
     * (약 64KB)이고, terraform_job_state.last_response(관찰 전용·best-effort)와 달리 완료 판정 tx 안에서 써진다 —
     * 외부 body가 컬럼을 넘기면 save가 깨져 판정 커밋이 롤백되고 재시도 루프에 갇힌다. UTF-8 4바이트 문자를
     * 가정해도 컬럼 바이트 한도 안에 들도록(16000×4 < 65535) 잡는다(terraform_result 로그 절단과 같은 취지).
     */
    int RESPONSE_MAX_LENGTH = 16_000;

    TaskOperation operation();

    /** operation 전용 조건 탐색 API를 호출해 충족 여부와 원시 payload를 함께 얻는다. */
    ConditionPoll check(String target);

    /**
     * 조건 응답을 방어하고 {@link ConditionPoll}로 만든다 — {@code met}이 null이면 쓸 수 없는 외부 응답이므로
     * CallFailed로 닫고, 그렇지 않으면 응답 body 원문을 payload로 싣는다({@code task_attempt.response}용). 원문은
     * operation별 DTO가 위임 역직렬화 시점에 {@code node.toString()}으로 이미 잡아 두므로 여기서 다시 직렬화하지 않는다.
     * body는 컬럼 한도로 잘라 완료 판정 tx를 지킨다.
     */
    static ConditionPoll poll(Boolean met, String rawResponse, TaskOperation operation) {
        if (met == null) {
            throw new CallFailedException("InfraManager returned no condition result for " + operation);
        }
        return new ConditionPoll(met, clampToColumn(rawResponse));
    }

    /** task_attempt.response(TEXT) 컬럼을 넘지 않게 원문을 자른다 — 판정 tx의 save가 크기로 실패하지 않도록. */
    private static String clampToColumn(String response) {
        if (response == null || response.length() <= RESPONSE_MAX_LENGTH) {
            return response;
        }
        return response.substring(0, RESPONSE_MAX_LENGTH);
    }
}
