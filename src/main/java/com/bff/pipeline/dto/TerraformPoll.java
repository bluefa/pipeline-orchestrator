package com.bff.pipeline.dto;

/**
 * Terraform 잡 폴링 결과를 실어 나르는 전송 값 객체(transport value)로, 영속되지 않는다.
 *
 * {@code state}는 정규화 전 원시 {@code terraformState} 문자열이고, {@code phase}는 그걸 정규화한 판정이다 —
 * 진행-시점 관찰({@code terraform_job_state})은 원시 {@code state}를, 완료 집계는 {@code phase}를 본다.
 * {@code failReason}은 job이 FAILED로 관측될 때 status 응답이 실어 온 실패 사유이고 그 외에는 null이다.
 * {@code response}는 그 폴이 받은 status 응답 body 전문(전 필드 보존)으로, 진행-시점 관찰이 그대로 남기는
 * 표시 전용 값이며 없으면 null이다.
 */
public record TerraformPoll(String state, Phase phase, String failReason, String response) {

    /** 폴 한 번의 판정. 세 값이 전부라 "미종결인데 성공" 같은 불가능한 조합이 애초에 표현되지 않는다. */
    public enum Phase { RUNNING, SUCCEEDED, FAILED }

    /** 집계의 "이 job이 손을 뗐나" 물음 — RUNNING만 미종결이다. */
    public boolean finished() {
        return phase != Phase.RUNNING;
    }

    /** 집계의 "이 job이 성공했나" 물음 — SUCCEEDED만 성공이다. */
    public boolean succeeded() {
        return phase == Phase.SUCCEEDED;
    }

    public static TerraformPoll running(String state) {
        return new TerraformPoll(state, Phase.RUNNING, null, null);
    }

    public static TerraformPoll success(String state) {
        return new TerraformPoll(state, Phase.SUCCEEDED, null, null);
    }

    public static TerraformPoll failure(String state, String failReason) {
        return new TerraformPoll(state, Phase.FAILED, failReason, null);
    }

    /** 판정은 그대로 두고 관찰용 응답 body만 붙인 사본 — toPoll이 정규화 직후 원문을 실을 때 쓴다. */
    public TerraformPoll withResponse(String response) {
        return new TerraformPoll(state, phase, failReason, response);
    }
}
