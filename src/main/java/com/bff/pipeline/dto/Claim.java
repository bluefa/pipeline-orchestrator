package com.bff.pipeline.dto;

/**
 * ADR-021 tx1이 발급하는 claim 토큰 — 잡은 {@code pipelineId}와 이 claim의 fencing token을 담는다.
 * {@code PipelineClaimer}가 만들어 {@code PipelineWorker}/{@code StepReporter}로 넘긴다. tx2의 guarded
 * write-back은 이 {@code token}이 {@code pipeline.claimed_by}와 일치할 때만 기록한다.
 */
public record Claim(long pipelineId, String token) { }
