package com.bff.pipeline.dto;

/**
 * CONDITION_CHECK 폴 한 번의 결과를 실어 나르는 전송 값 객체(transport value)로, 영속되지 않는다.
 * {@code met}은 조건 충족 여부이고, {@code response}는 그 폴이 돌려준 원시 check payload다 —
 * 엔진이 이 폴의 {@code task_attempt.response}에 그대로 기록한다(ADR-016 §3, Schema).
 * TERRAFORM_JOB의 dispatch response(job id 집합)와 대칭이나, 조건은 dispatch가 no-op이라 payload가
 * check 시점에 온다.
 */
public record ConditionPoll(boolean met, String response) { }
