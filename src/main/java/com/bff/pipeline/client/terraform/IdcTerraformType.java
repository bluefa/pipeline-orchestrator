package com.bff.pipeline.client.terraform;

/**
 * IDC Terraform API의 {@code idcTerraformType} 쿼리 파라미터 값이다. CX → BDP 실행 순서 제약은 InfraManager
 * 서비스 레이어가 강제하고, 이 모듈에서는 recipe의 task 순서가 같은 것을 보장한다 — 클라이언트는 값만 전달한다.
 */
public enum IdcTerraformType {
    CX,
    BDP
}
