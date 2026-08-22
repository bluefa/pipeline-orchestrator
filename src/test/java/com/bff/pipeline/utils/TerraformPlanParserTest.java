package com.bff.pipeline.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.enums.TerraformChangeKind;
import com.bff.pipeline.model.TerraformPlan;
import com.bff.pipeline.model.TerraformPlan.Change;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * plan 로그 파싱의 어휘와 대조 규칙을 고정한다. 요약이 승인 판단의 근거가 되므로, 여기서 지켜야 할 것은
 * "정확히 세는 것"과 "못 셌을 때 못 셌다고 말하는 것" 둘이다 — 후자가 없으면 삭제 건수가 빠진 요약을
 * 보고 승인하는 경로가 열린다.
 */
class TerraformPlanParserTest {

    @Test
    void readsEveryChangeVocabularyTerraformPrints() {
        TerraformPlan parsed = TerraformPlanParser.parse("""
                  # aws_instance.web will be created
                  # aws_security_group.sg will be updated in-place
                  # aws_s3_bucket.logs will be destroyed
                  # aws_db_instance.main must be replaced
                  # aws_iam_role.legacy will no longer be managed by Terraform, but will not be destroyed
                  # aws_vpc.imported will be imported
                  # aws_subnet.old has moved to aws_subnet.new

                Plan: 1 to import, 2 to add, 1 to change, 2 to destroy, 1 to forget.
                """);

        assertThat(parsed.consistent()).isTrue();
        assertThat(count(parsed, TerraformChangeKind.CREATE)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.UPDATE)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.DESTROY)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.REPLACE)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.FORGET)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.IMPORT)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.MOVE)).isEqualTo(1);
    }

    /**
     * 교체는 합계 줄에 자기 항목이 없고 생성 1건 + 삭제 1건으로 흩어져 적힌다. 그래서 대조할 때 양쪽에
     * 더해야 하며, 이걸 놓치면 교체가 있는 모든 plan이 "합계 불일치"로 떨어진다.
     */
    @Test
    void aReplacementCountsTowardBothAddAndDestroyInTheTotals() {
        TerraformPlan parsed = TerraformPlanParser.parse("""
                  # aws_db_instance.main is tainted, so must be replaced

                Plan: 1 to add, 0 to change, 1 to destroy.
                """);

        assertThat(parsed.consistent()).isTrue();
        assertThat(count(parsed, TerraformChangeKind.REPLACE)).isEqualTo(1);
        assertThat(count(parsed, TerraformChangeKind.CREATE)).isZero();
        assertThat(count(parsed, TerraformChangeKind.DESTROY)).isZero();
    }

    @Test
    void noChangesIsAValidPlanWithNothingInIt() {
        TerraformPlan parsed = TerraformPlanParser.parse(
                "No changes. Your infrastructure matches the configuration.");

        assertThat(parsed.consistent()).isTrue();
        assertThat(parsed.changes()).isEmpty();
    }

    /** 로그가 앞에서 잘려 변경 줄이 사라지면 합계와 어긋난다 — 이때 "변경 0건"으로 보고하면 거짓말이 된다. */
    @Test
    void aTruncatedBodyIsReportedAsInconsistentNotAsZeroChanges() {
        TerraformPlan parsed = TerraformPlanParser.parse("""
                  # aws_instance.web will be created

                Plan: 3 to add, 0 to change, 2 to destroy.
                """);

        assertThat(parsed.consistent()).isFalse();
    }

    @Test
    void aLogWithNeitherTotalsNorTheNoChangesLineIsInconsistent() {
        assertThat(TerraformPlanParser.parse("terraform: ok").consistent()).isFalse();
        assertThat(TerraformPlanParser.parse("").consistent()).isFalse();
        assertThat(TerraformPlanParser.parse(null).consistent()).isFalse();
    }

    /** 색을 입은 로그가 그대로 저장돼 있어도 읽어야 한다 — 색 코드 때문에 주소가 훼손되면 안 된다. */
    @Test
    void stripsTerminalColourCodesWithoutDamagingResourceAddresses() {
        TerraformPlan parsed = TerraformPlanParser.parse("""
                  \u001B[1m# aws_instance.web[0]\u001B[0m will be created

                Plan: 1 to add, 0 to change, 0 to destroy.
                """);

        assertThat(parsed.consistent()).isTrue();
        assertThat(parsed.changes()).extracting(Change::address).containsExactly("aws_instance.web[0]");
    }

    /** 위험 순서는 요약이 목록을 줄일 때 무엇을 남길지 정한다 — 교체·삭제가 생성·수정보다 앞이어야 한다. */
    @Test
    void changeKindsAreDeclaredInRiskOrder() {
        assertThat(Arrays.asList(TerraformChangeKind.values())).containsExactly(
                TerraformChangeKind.REPLACE, TerraformChangeKind.DESTROY, TerraformChangeKind.FORGET,
                TerraformChangeKind.IMPORT, TerraformChangeKind.CREATE, TerraformChangeKind.UPDATE,
                TerraformChangeKind.MOVE);
    }

    private static long count(TerraformPlan plan, TerraformChangeKind kind) {
        return plan.changes().stream().filter(change -> change.kind() == kind).count();
    }
}
