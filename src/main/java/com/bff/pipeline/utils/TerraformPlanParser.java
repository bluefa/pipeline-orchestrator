package com.bff.pipeline.utils;

import com.bff.pipeline.enums.TerraformChangeKind;
import com.bff.pipeline.model.TerraformPlan;
import com.bff.pipeline.model.TerraformPlan.Change;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * terraform plan 로그 본문에서 "무엇이 어떻게 바뀌는가"만 읽어 내는 순수 함수다(승인 게이트 ADR §결정 5).
 * 승인자에게 보여줄 요약의 재료를 만들 뿐이라 상태 전이에는 아무 영향도 주지 않으며, 그래서 빈이 아니라
 * 정적 유틸리티다.
 *
 * plan 텍스트는 리소스마다 한 줄로 무엇을 할지 적고, 마지막에 합계 한 줄을 적는다. 파서는 두 가지를
 * 모두 읽은 뒤 서로 맞는지 대조한다. 리소스 줄을 세어 나온 수와 terraform이 스스로 적은 합계가
 * 어긋나면 파싱이 그 로그를 제대로 못 읽었다는 뜻이므로, 조용히 넘어가지 않고 "맞지 않음"을 보고한다 —
 * 반쪽짜리 요약을 근거로 승인이 나가는 것이 이 기능에서 가장 나쁜 결말이기 때문이다.
 *
 * 대조에서 주의할 점이 하나 있다. 교체(replace)는 합계 줄에 별도 항목이 없고 생성 1건 + 삭제 1건으로
 * 흩어져 적힌다. 그래서 교체는 리소스 줄에서 따로 세되 대조할 때는 생성·삭제 양쪽에 더한다. 이동(moved)은
 * 애초에 합계에 들어가지 않으므로 대조에서 뺀다.
 *
 * 세지 못하는 것도 분명히 해 둔다: 바뀌지 않는 리소스는 plan 텍스트에 아예 인쇄되지 않아 "변경 없음
 * N건"은 알 수 없다. 속성 단위로 무엇이 어떻게 달라지는지도 여기서는 다루지 않는다 — 그 수준이 필요해지면
 * 로그 텍스트가 아니라 구조화된 plan 출력을 받아야 한다.
 */
public final class TerraformPlanParser {

    /**
     * 터미널 색상 제어 문자. plan 로그가 색을 입은 채 저장돼 있을 수 있어 파싱 전에 걷어낸다.
     * 시작의 escape 문자까지 함께 지워야 한다 — 대괄호부터 지우면 {@code aws_instance.web[0]} 같은
     * 정상 주소의 인덱스가 덩달아 날아간다.
     */
    private static final Pattern ANSI = Pattern.compile("\\e\\[[0-9;]*[a-zA-Z]");

    /** 리소스 한 줄: 앞의 {@code #} 뒤 주소와, 그 뒤에 오는 동작 문구. */
    private static final Pattern RESOURCE_LINE = Pattern.compile(
            "^\\s*#\\s+(.+?)\\s+(?:is tainted, so )?(will be created|will be updated in-place|will be destroyed"
                    + "|must be replaced|will be replaced|will be imported|will no longer be managed|has moved to)\\b");

    /** 합계 줄의 항목 하나 — "2 to add" 처럼 수와 이름이 붙어 나온다. */
    private static final Pattern TOTAL_ITEM = Pattern.compile("(\\d+)\\s+to\\s+(add|change|destroy|import|forget)");

    private static final Pattern TOTAL_LINE = Pattern.compile("^\\s*Plan:\\s+.*$", Pattern.MULTILINE);

    /** 바뀔 것이 없을 때 terraform이 적는 문장. 이때는 합계 줄이 아예 나오지 않는다. */
    private static final Pattern NO_CHANGES = Pattern.compile("No changes\\.");

    private TerraformPlanParser() {
    }

    public static TerraformPlan parse(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return new TerraformPlan(List.of(), false);
        }
        String log = ANSI.matcher(rawLog).replaceAll("");
        List<Change> changes = readResourceLines(log);
        Map<String, Integer> totals = readTotals(log);
        return new TerraformPlan(changes, agrees(changes, totals, log));
    }

    private static List<Change> readResourceLines(String log) {
        List<Change> changes = new ArrayList<>();
        for (String line : log.split("\\R")) {
            Matcher matcher = RESOURCE_LINE.matcher(line);
            if (matcher.find()) {
                changes.add(new Change(matcher.group(1).trim(), kindOf(matcher.group(2))));
            }
        }
        return List.copyOf(changes);
    }

    private static TerraformChangeKind kindOf(String phrase) {
        return switch (phrase) {
            case "will be created" -> TerraformChangeKind.CREATE;
            case "will be updated in-place" -> TerraformChangeKind.UPDATE;
            case "will be destroyed" -> TerraformChangeKind.DESTROY;
            case "must be replaced", "will be replaced" -> TerraformChangeKind.REPLACE;
            case "will be imported" -> TerraformChangeKind.IMPORT;
            case "will no longer be managed" -> TerraformChangeKind.FORGET;
            case "has moved to" -> TerraformChangeKind.MOVE;
            default -> throw new IllegalStateException("unhandled terraform plan phrase: " + phrase);
        };
    }

    /** 합계 줄을 항목별 수로 읽는다. 줄이 없으면 빈 결과이고, 그 구분은 {@link #agrees}가 "변경 없음" 문장으로 한다. */
    private static Map<String, Integer> readTotals(String log) {
        Matcher totalLine = TOTAL_LINE.matcher(log);
        if (!totalLine.find()) {
            return Map.of();
        }
        Map<String, Integer> totals = new HashMap<>();
        Matcher item = TOTAL_ITEM.matcher(totalLine.group());
        while (item.find()) {
            totals.put(item.group(2), Integer.parseInt(item.group(1)));
        }
        return totals;
    }

    /**
     * 리소스 줄에서 센 수와 합계 줄이 맞는지 대조한다. 합계 줄이 없으면 "바뀔 것 없음" 문장이 있고 읽어낸
     * 리소스도 없을 때만 맞다고 본다 — 둘 다 아니면 로그가 잘렸거나 우리가 못 읽은 것이다.
     */
    private static boolean agrees(List<Change> changes, Map<String, Integer> totals, String log) {
        Map<TerraformChangeKind, Integer> counted = new EnumMap<>(TerraformChangeKind.class);
        for (Change change : changes) {
            counted.merge(change.kind(), 1, Integer::sum);
        }
        if (totals.isEmpty()) {
            return changes.isEmpty() && NO_CHANGES.matcher(log).find();
        }
        int replaced = counted.getOrDefault(TerraformChangeKind.REPLACE, 0);
        return matches(totals, "add", counted.getOrDefault(TerraformChangeKind.CREATE, 0) + replaced)
                && matches(totals, "change", counted.getOrDefault(TerraformChangeKind.UPDATE, 0))
                && matches(totals, "destroy", counted.getOrDefault(TerraformChangeKind.DESTROY, 0) + replaced)
                && matches(totals, "import", counted.getOrDefault(TerraformChangeKind.IMPORT, 0))
                && matches(totals, "forget", counted.getOrDefault(TerraformChangeKind.FORGET, 0));
    }

    /** 합계 줄에 없는 항목은 0으로 본다 — terraform은 해당 없는 항목을 아예 적지 않는다. */
    private static boolean matches(Map<String, Integer> totals, String item, int counted) {
        return totals.getOrDefault(item, 0) == counted;
    }
}
