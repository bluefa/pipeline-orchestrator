package com.bff.pipeline.dto;

/**
 * ADR-021 tx1이 발급한 claim 토큰: 대상 {@code pipelineId}와 이 claim의 fencing token이다.
 * {@code PipelineClaimer}가 생성해 {@code PipelineWorker}/{@code StepReporter}로 전달하며, tx2의
 * guarded write-back은 이 {@code token}이 {@code pipeline.claimed_by}와 일치할 때만 기록한다.
 */
public record Claim(long pipelineId, String token) { }
