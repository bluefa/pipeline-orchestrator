package com.bff.pipeline.model;

import com.bff.pipeline.client.InstallationOperationsClient.Recommendation;

/**
 * Task 시작 호출의 결과를 전달한다. 기존 비동기 작업의 응답/무응답과 HTTP 입력 준비·동기 완료를 구분하므로
 * 상태 기록자는 모든 결과를 빠짐없이 처리한다. 추천 GET 성공은 입력을 고정하는 중간 결과이며 Task 완료가 아니다.
 */
public sealed interface DispatchResult permits DispatchResult.WithResponse, DispatchResult.None, DispatchResult.HttpPrepared, DispatchResult.HttpCompleted {

    DispatchResult NONE = new None();

    record WithResponse(String response) implements DispatchResult { }

    record None() implements DispatchResult { }
    record HttpPrepared(Recommendation recommendation) implements DispatchResult { }
    record HttpCompleted(HttpTaskResult result) implements DispatchResult { }

    static DispatchResult withResponse(String response) {
        return new WithResponse(response);
    }
}
