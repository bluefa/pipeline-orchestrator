package com.bff.pipeline.dto;

/**
 * Terraform 잡 폴링 결과를 담는 전송 값 객체(transport value)로, 영속되지 않는다.
 * compact constructor는 불가능한 상태({@code !finished && succeeded})를 거부하여
 * 불변식을 강제한다: 완료되지 않은 폴은 성공 상태일 수 없다.
 */
public record TerraformPoll(boolean finished, boolean succeeded) {

    public TerraformPoll {
        if (!finished && succeeded) { throw new IllegalArgumentException("a not-finished poll cannot be succeeded"); }
    }

    public static TerraformPoll running() {
        return new TerraformPoll(false, false);
    }

    public static TerraformPoll success() {
        return new TerraformPoll(true, true);
    }

    public static TerraformPoll failure() {
        return new TerraformPoll(true, false);
    }
}
