package com.bff.pipeline.model;

import com.bff.pipeline.enums.TerraformChangeKind;
import java.util.List;

/**
 * terraform plan 로그에서 읽어 낸 변경 목록이다. {@code TerraformPlanParser}가 만들고 승인 요약이 소비한다.
 *
 * {@code consistent}가 이 값의 핵심이다. plan 텍스트는 리소스마다 한 줄로 무엇을 할지 적고 마지막에 합계
 * 한 줄을 적는데, 파서는 둘을 모두 읽고 서로 맞는지 대조한다. 어긋나면 우리가 그 로그를 제대로 못 읽었다는
 * 뜻이므로 거짓이 되고, 소비자는 수치를 쓰지 않고 "검증 불가"로 표시한다 — 반쪽짜리 요약을 근거로 승인이
 * 나가는 것이 이 기능에서 가장 나쁜 결말이기 때문이다.
 */
public record TerraformPlan(List<Change> changes, boolean consistent) {

    public TerraformPlan {
        changes = List.copyOf(changes);
    }

    /** 리소스 한 건의 변경 — 주소와 무엇을 하는지. */
    public record Change(String address, TerraformChangeKind kind) { }
}
