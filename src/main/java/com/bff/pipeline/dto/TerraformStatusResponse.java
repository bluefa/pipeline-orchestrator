package com.bff.pipeline.dto;

/**
 * InfraManager의 terraform job 상태 조회 응답 전송 값이다. {@code InfraManagerFeignAdapter}가 이를 도메인의
 * {@link TerraformPoll}로 변환한다(불가능한 조합 {@code !finished && succeeded}는 TerraformPoll의 compact
 * constructor가 거부한다).
 */
public record TerraformStatusResponse(boolean finished, boolean succeeded) { }
