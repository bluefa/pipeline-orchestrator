package com.bff.pipeline.client.terraform;

import java.util.List;

/**
 * InfraManager Terraform API의 job 타입이다 — 일부 엔드포인트의 {@code jobType} 쿼리 파라미터 값이자, 폴 정규화의
 * "기대 성공 상태" authority다. 성공을 뜻하는 terminal 상태가 타입마다 여러 개일 수 있다(owner 확정): PLAN은
 * {@code CREATED} 또는 {@code COMPLETED}, DESTROY는 {@code COMPLETED} 또는 {@code DESTROYED}, APPLY는
 * {@code COMPLETED}. {@code FAILED}는 타입 공통 실패 terminal이다.
 *
 * TODO: {@code TerraformState}의 전체 값 목록이 확정되면(설계 §6) 이 String 상수들을 닫힌 enum으로 교체한다 —
 * 교체 지점은 여기와 {@link TerraformOperationBinding#toPoll} 뿐이다. 그때까지 성공/실패 terminal 값만 해석하고
 * 나머지 문자열은 전부 "진행 중"으로 본다.
 */
public enum TerraformJobType {

    PLAN("CREATED", "COMPLETED"),
    APPLY("COMPLETED"),
    DESTROY("COMPLETED", "DESTROYED");

    /** 타입 공통 실패 terminal 상태. */
    public static final String FAILED_STATE = "FAILED";

    private final List<String> successStates;

    TerraformJobType(String... successStates) {
        this.successStates = List.of(successStates);
    }

    /** 이 job 타입에서 성공을 뜻하는 terminal {@code terraformState} 값들. */
    public List<String> successStates() {
        return successStates;
    }

    /** 주어진 {@code terraformState}가 이 job 타입의 성공 terminal 중 하나인지. */
    public boolean isSuccess(String state) {
        return successStates.contains(state);
    }
}
