package com.bff.pipeline.dto.pipeline;

import com.bff.pipeline.enums.TerraformChangeKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;

/**
 * 승인 화면에 보여줄 plan 요약이다(승인 게이트 ADR §결정 5). terraform plan 로그는 문법에 익숙한 사람만
 * 읽을 수 있는 수백~수천 줄이라, 승인자가 "무엇이 몇 건 생기고 몇 건 사라지는가"를 한눈에 보게 하려고
 * 백엔드가 미리 뽑아 둔다. 콘솔과 Slack이 같은 값을 소비한다 — 화면마다 따로 파싱하지 않는다.
 *
 * {@code verified}가 이 요약의 핵심이다. 원천인 plan 로그는 저장에 실패할 수도, 잘릴 수도 있는
 * 관찰 데이터다. 그래서 로그가 전부 있고, 잘리지 않았고, 변경 목록과 합계가 맞아떨어질 때에만 참이 되고,
 * 하나라도 어긋나면 수치를 아예 내보내지 않고 거짓 + {@code unverifiedReason}만 담는다. 반쪽짜리
 * 요약(예: 삭제 건수가 빠진 요약)을 근거로 승인이 나가는 경로를 막는 것이 이 규칙의 목적이다.
 *
 * 그래서 수치 필드는 원시 타입이 아니라 null이 될 수 있는 타입이고, null인 필드는 JSON에서 아예
 * 빠진다. 원시 타입이면 검증 실패 요약도 {@code create_count: 0}처럼 수치를 주장하게 되는데, 이는
 * "0건 생성"으로 읽혀 사실과 다르다 — 우리가 아는 것은 0건이 아니라 "모른다"이기 때문이다. 소비자는
 * {@code verified}가 거짓이면 수치 키가 없다고 보고 원문 로그를 보여 주면 된다.
 *
 * 주소 목록은 위험한 것부터(교체·삭제 우선) 정해진 개수까지만 담는다. 무제한 목록을 승인 요청을
 * 만드는 트랜잭션에 실으면 큰 plan 하나가 저장 실패 → 게이트 진입 실패 → 같은 파이프라인이 계속 다시
 * 잡히는 고리를 만들 수 있기 때문이다. 잘렸다면 {@code addressesTruncated}와 {@code omittedCount}가
 * 그 사실을 밝히며, 집계 수치는 잘림과 무관하게 언제나 온전하다.
 *
 * 이 값은 JSON 문자열로 승인 행에 저장되고 조회 응답에 그대로 실린다. 와이어 필드는 snake_case다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanSummary(
        @JsonProperty("verified") boolean verified,
        @JsonProperty("unverified_reason") String unverifiedReason,
        @JsonProperty("create_count") Long createCount,
        @JsonProperty("update_count") Long updateCount,
        @JsonProperty("destroy_count") Long destroyCount,
        @JsonProperty("replace_count") Long replaceCount,
        @JsonProperty("import_count") Long importCount,
        @JsonProperty("forget_count") Long forgetCount,
        @JsonProperty("move_count") Long moveCount,
        @JsonProperty("changes") List<ChangeView> changes,
        @JsonProperty("addresses_truncated") Boolean addressesTruncated,
        @JsonProperty("omitted_count") Integer omittedCount) {

    /** 요약에 실리는 변경 한 건 — 리소스 주소와 무엇을 하는지. */
    public record ChangeView(
            @JsonProperty("address") String address,
            @JsonProperty("kind") TerraformChangeKind kind) {
    }
}
