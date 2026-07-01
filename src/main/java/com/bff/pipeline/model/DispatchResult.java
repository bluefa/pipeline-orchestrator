package com.bff.pipeline.model;

/**
 * task를 시작하는 dispatch({@link TaskType#execute})의 결과를 담는 봉인(sealed) 타입이다(ADR-016 ed97ec0 §3/§5).
 * 엔진은 이 값만 보고 {@code task_attempt.response}(text)에 무엇을 기록할지 exhaustive하게 결정한다.
 *
 * <ul>
 *   <li>{@link WithResponse} — dispatch가 <b>원시 응답 텍스트</b>를 돌려준 경우. 엔진은 형식을 해석하지 않고
 *       {@code task_attempt.response}에 그대로 저장하고, 완료를 판정할 때 각 {@link TaskType}이 자기 형식으로
 *       역직렬화한다. 응답 스키마는 전적으로 해당 task type의 사적 계약이다.</li>
 *   <li>{@link #NONE} — 디스패치할 대상이 없는 순수 폴링 타입의 <b>응답 없음(void)</b> 결과. 기록할 게 없다.</li>
 * </ul>
 */
public sealed interface DispatchResult permits DispatchResult.WithResponse, DispatchResult.None {

    DispatchResult NONE = new None();

    record WithResponse(String response) implements DispatchResult { }

    record None() implements DispatchResult { }

    static DispatchResult withResponse(String response) {
        return new WithResponse(response);
    }
}
