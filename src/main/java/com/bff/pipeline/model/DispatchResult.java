package com.bff.pipeline.model;

import java.time.Instant;

/**
 * task를 시작하는 dispatch({@link TaskType#execute})의 결과를 담는 봉인(sealed) 타입이다(ADR-016 ed97ec0 §3/§5).
 * 엔진은 이 값만 보고 {@code task_attempt.response}(text)에 무엇을 기록할지 exhaustive하게 결정한다.
 *
 * <ul>
 *   <li>{@link WithResponse} — dispatch가 <b>원시 응답 텍스트</b>를 돌려준 경우. 엔진은 형식을 해석하지 않고
 *       {@code task_attempt.response}에 그대로 저장하고, 완료를 판정할 때 각 {@link TaskType}이 자기 형식으로
 *       역직렬화한다. 응답 스키마는 전적으로 해당 task type의 사적 계약이다.</li>
 *   <li>{@link #NONE} — 디스패치할 대상이 없는 순수 폴링 타입의 <b>응답 없음(void)</b> 결과. 기록할 게 없다.</li>
 *   <li>{@link AwaitApproval} — 승인 게이트가 외부 작업 대신 사람의 결정을 기다리기 시작한 결과.
 *       엔진은 IN_PROGRESS 대신 AWAIT_APPROVAL로 전이하고, 같은 트랜잭션에서 승인 요청 행을 만든다
 *       (승인 게이트 ADR §결정 2).</li>
 * </ul>
 */
public sealed interface DispatchResult
        permits DispatchResult.WithResponse, DispatchResult.None, DispatchResult.AwaitApproval {

    DispatchResult NONE = new None();

    record WithResponse(String response) implements DispatchResult { }

    record None() implements DispatchResult { }

    /**
     * 승인 대기 진입. {@code expiresAt}은 디스패치 시점에 고정되는 승인 만료 시각이고 파이프라인이 다시
     * 잡히는 시각이기도 하다. {@code planSummary}는 승인 화면에 보여줄 요약 JSON이며 표시 전용이라
     * null이어도 게이트는 정상 동작한다.
     */
    record AwaitApproval(Instant expiresAt) implements DispatchResult { }

    static DispatchResult withResponse(String response) {
        return new WithResponse(response);
    }

    static DispatchResult awaitApproval(Instant expiresAt) {
        return new AwaitApproval(expiresAt);
    }
}
