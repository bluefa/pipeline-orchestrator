package com.bff.pipeline.model;

import com.bff.pipeline.dto.NotifyPayload;

/**
 * ADR-022 notify claim 트랜잭션이 발급하는 claim 토큰 — 잡은 {@code pipelineId}, 이 claim의 notify 전용
 * fencing {@code token}, 그리고 claim 트랜잭션 안에서 이미 커밋된 pipeline/task 행으로 구성한 {@code payload}를
 * 담는다. {@code NotifyClaimer}가 만들어 {@code NotifyScheduler}로 넘기고, write-back 트랜잭션
 * ({@code NotifyWriteBack})의 가드는 이 {@code token}이 {@code pipeline.notify_claimed_by}와 일치할 때만
 * 기록한다. 전송되지 않는 내부 handoff 값이라 dto가 아닌 model에 둔다(payload만 sink로 나간다).
 */
public record NotifyClaim(long pipelineId, String token, NotifyPayload payload) { }
