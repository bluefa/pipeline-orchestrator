package com.bff.pipeline.dto;

/**
 * Terraform 잡 폴링 결과를 실어 나르는 전송 값 객체(transport value)로, 영속되지 않는다.
 * compact constructor가 불가능한 상태({@code !finished && succeeded})를 막아 불변식을 지킨다 —
 * 아직 끝나지 않은 폴은 성공일 수 없다.
 *
 * <p>{@code resultPath}는 status 응답이 실어 온 결과 파일 포인터(스토리지 URI)로, postCheck 관찰(확장 A)이
 * {@code terraform_result} 행에 원본 전문 포인터로 남긴다. terminal 폴에만 의미가 있고 없을 수 있다(nullable).
 */
public record TerraformPoll(boolean finished, boolean succeeded, String resultPath) {

    public TerraformPoll {
        if (!finished && succeeded) { throw new IllegalArgumentException("a not-finished poll cannot be succeeded"); }
    }

    public static TerraformPoll running() {
        return new TerraformPoll(false, false, null);
    }

    public static TerraformPoll success() {
        return new TerraformPoll(true, true, null);
    }

    public static TerraformPoll success(String resultPath) {
        return new TerraformPoll(true, true, resultPath);
    }

    public static TerraformPoll failure() {
        return new TerraformPoll(true, false, null);
    }

    public static TerraformPoll failure(String resultPath) {
        return new TerraformPoll(true, false, resultPath);
    }
}
