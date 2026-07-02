package com.bff.pipeline.client.terraform;

/**
 * InfraManager Terraform API의 job 타입이다 — 일부 엔드포인트의 {@code jobType} 쿼리 파라미터 값이자, 폴 정규화의
 * "기대 성공 상태" authority다. 성공을 뜻하는 terminal 상태가 타입마다 다르다(owner 확정): PLAN/APPLY는
 * {@code COMPLETED}, DESTROY는 {@code DESTROYED}. {@code FAILED}는 타입 공통 실패 terminal이다.
 *
 * <p>TODO: {@code TerraformState}의 전체 값 목록이 확정되면(설계 §6) 이 String 상수들을 닫힌 enum으로 교체한다 —
 * 교체 지점은 여기와 {@link TerraformOperationBinding#toPoll} 뿐이다. 그때까지 세 terminal 값만 해석하고
 * 나머지 문자열은 전부 "진행 중"으로 본다.
 */
public enum TerraformJobType {

    PLAN("COMPLETED"),
    APPLY("COMPLETED"),
    DESTROY("DESTROYED");

    /** 타입 공통 실패 terminal 상태. */
    public static final String FAILED_STATE = "FAILED";

    private final String successState;

    TerraformJobType(String successState) {
        this.successState = successState;
    }

    /** 이 job 타입에서 성공을 뜻하는 terminal {@code terraformState} 값. */
    public String successState() {
        return successState;
    }
}
