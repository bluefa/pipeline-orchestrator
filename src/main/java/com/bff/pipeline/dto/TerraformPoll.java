package com.bff.pipeline.dto;

/**
 * Terraform 잡 폴링 결과를 실어 나르는 전송 값 객체(transport value)로, 영속되지 않는다.
 * compact constructor가 불가능한 상태({@code !finished && succeeded})를 막아 불변식을 지킨다 —
 * 아직 끝나지 않은 폴은 성공일 수 없다.
 *
 * {@code state}는 정규화 전 원시 {@code terraformState} 문자열이다 — 판정({@code finished}/{@code succeeded})은
 * 이걸 정규화한 값이지만, 진행-시점 관찰({@code terraform_job_state})은 원시 문자열 그대로를 남긴다.
 * {@code failReason}은 job이 FAILED로 관측될 때 status 응답이 실어 온 실패 사유이고, 그 외에는 null이다(nullable).
 */
public record TerraformPoll(String state, boolean finished, boolean succeeded, String failReason) {

    public TerraformPoll {
        if (!finished && succeeded) { throw new IllegalArgumentException("a not-finished poll cannot be succeeded"); }
    }

    public static TerraformPoll running(String state) {
        return new TerraformPoll(state, false, false, null);
    }

    public static TerraformPoll success(String state) {
        return new TerraformPoll(state, true, true, null);
    }

    public static TerraformPoll failure(String state, String failReason) {
        return new TerraformPoll(state, true, false, failReason);
    }
}
