package com.bff.pipeline.service.approval;

import com.bff.pipeline.dto.pipeline.PlanSummary;
import com.bff.pipeline.dto.pipeline.PlanSummary.ChangeView;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskApproval;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TerraformResult;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.enums.TerraformChangeKind;
import com.bff.pipeline.model.TerraformPlan;
import com.bff.pipeline.model.TerraformPlan.Change;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.utils.TerraformDispatchResponse;
import com.bff.pipeline.utils.TerraformPlanParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 승인 요청에 실을 plan 요약을 만든다(승인 게이트 ADR §결정 5). 재료는 이미 저장돼 있다 — 직전 Plan
 * 단계가 끝날 때 terraform 로그가 {@code terraform_result}에 남기 때문에, 새로 수집할 것이 없다.
 *
 * 읽기의 범위를 여기서 못박는다. 엔진은 원래 terraform 로그를 읽지 않는다(ADR-016). 이 클래스가
 * 그 규칙의 유일한 예외이며, 예외의 범위는 "승인 화면에 보여줄 payload를 만드는 읽기"뿐이다. 게이트가
 * 다음에 무엇을 할지는 승인 행의 상태와 만료 시각만 보고 정하므로, 여기서 무슨 일이 나든 게이트의 진행은
 * 달라지지 않는다.
 *
 * 그래서 규칙이 하나 더 붙는다 — 확신이 없으면 수치를 내보내지 않는다. 로그가 없거나, 일부
 * 작업의 로그가 빠졌거나, 본문이 잘렸거나, 변경 목록과 합계가 맞지 않으면 요약은 수치 없이 "검증 불가"로
 * 나간다. 승인자는 그때 콘솔에서 원문을 직접 본다. 얼추 맞는 요약을 보여 주는 것보다 못 읽었다고 말하는
 * 편이 낫다는 판단이다.
 *
 * 여기까지 오는 "검증 불가"는 대개 다시 돌려도 그대로인 것들이다. 본문 자체가 남지 않은 결손은 그 앞에서
 * 걸러지기 때문이다 — Plan을 끝내는 쪽이 근거를 남기지 못한 시도를 성공으로 닫지 않고 다시 돌린다
 * ({@link PlanLogEvidence}). 원문도 요약도 없는 화면을 승인자에게 내미는 것은 그쪽에서 막고, 이쪽은 읽을
 * 원문은 있으나 해석이 안 되는 경우를 맡는다.
 *
 * 결과 JSON은 승인 행의 컬럼 한도 안으로 맞춘다. 큰 plan의 주소 목록이 컬럼을 넘겨 저장이 깨지면 게이트
 * 진입 자체가 롤백되고 같은 파이프라인이 계속 다시 잡히므로, 한도를 넘으면 목록을 줄여서라도 집계 수치와
 * 잘림 표식은 반드시 살린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanSummaryExtractor {

    /** 주소 목록에 담을 최대 건수와, 한도를 넘겼을 때 줄여 볼 단계. 마지막 단계는 목록을 완전히 비운다. */
    private static final List<Integer> ADDRESS_LIMITS = List.of(20, 5, 0);

    private final TaskRepository tasks;
    private final TaskAttemptRepository taskAttempts;
    private final TerraformResultRepository terraformResults;
    private final ObjectMapper objectMapper;

    /**
     * 게이트 앞 Plan 단계의 로그에서 요약 JSON을 만든다. 어떤 이유로든 만들지 못하면 예외 대신 "검증 불가"
     * 요약을 돌려준다 — 요약은 표시용이고, 이것 때문에 승인 요청 자체가 못 만들어지면 안 된다.
     */
    public String summarize(Task gate) {
        try {
            return writeBounded(build(gate));
        } catch (RuntimeException failure) {
            // harness-allow: targeted-catch — 표시 전용 계약의 경계다. 요약 생성 실패가 게이트 진입을
            // 막으면 승인 요청이 아예 안 만들어지므로, 여기서 "검증 불가"로 강등하고 원인은 로그로 남긴다.
            // 관찰 recorder들과 달리 인터럽트를 되던지지 않는다 — 여기서는 외부 호출을 하지 않아
            // CallInterruptedException이 발생할 자리가 없고, 없는 예외를 되던지는 코드는 계약을 흐린다.
            log.warn("task {}: plan summary extraction failed — falling back to unverified", gate.getId(), failure);
            return writeUnverified("요약을 만드는 중 오류가 났습니다");
        }
    }

    /** 검증을 통과한 변경 목록이거나, 통과하지 못한 이유가 담긴 요약이다. */
    private sealed interface Extraction {
        record Changes(List<Change> changes) implements Extraction { }

        record Unverified(String reason) implements Extraction { }
    }

    private Extraction build(Task gate) {
        Task planTask = ApprovalPlanSource
                .forGate(gate, tasks.findByPipelineIdOrderBySequenceAsc(gate.getPipelineId()))
                .orElse(null);
        if (planTask == null) {
            return new Extraction.Unverified("직전 Plan 단계를 찾지 못했습니다");
        }
        if (planTask.getStatus() != TaskStatus.DONE) {
            return new Extraction.Unverified("직전 Plan 단계가 성공으로 끝나지 않았습니다");
        }
        // 성공으로 끝난 시도의 번호. failCount는 시도가 실패로 닫힐 때만 오르므로 이 값이 곧 마지막 시도다.
        int attemptNumber = planTask.getFailCount() + 1;
        List<String> dispatchedJobs = dispatchedJobIds(planTask.getId(), attemptNumber);
        if (dispatchedJobs.isEmpty()) {
            return new Extraction.Unverified("Plan 단계가 던진 작업 목록을 읽지 못했습니다");
        }
        return parseEachJob(planTask.getId(), attemptNumber, dispatchedJobs);
    }

    /**
     * 던진 job을 하나씩 돌며 그 job의 로그만 읽어 파싱하고, 본문은 다음 job으로 넘어가기 전에 버린다.
     * 한 시도의 로그를 통째로 읽어 오지 않는 이유는 본문이 최대 4백만 자까지 갈 수 있어서다 — job 세 개짜리
     * plan이면 그것만으로 수십 MB이고, 게이트 진입은 트랜잭션 밖 워커 스레드에서 도는 데다 여러 파이프라인이
     * 같은 sweep에 진입할 수 있다. 거기서 메모리가 터지면 claim이 반납되지 않은 채 lease가 만료되고, 다시
     * 잡혀 같은 자리에서 또 터진다.
     *
     * 로그가 없는 job을 만나면 그 자리에서 검증 불가로 닫는다 — 던진 목록이 기준이므로, 행이 없다는 것이
     * 곧 그 job의 변경이 요약에서 빠진다는 뜻이다.
     */
    private Extraction parseEachJob(Long planTaskId, int attemptNumber, List<String> jobIds) {
        List<Change> all = new ArrayList<>();
        for (String jobId : jobIds) {
            Extraction parsed = terraformResults
                    .findByTaskIdAndAttemptNumberAndJobId(planTaskId, attemptNumber, jobId)
                    .map(PlanSummaryExtractor::parseOne)
                    .orElseGet(() -> new Extraction.Unverified("일부 Plan 작업의 로그가 없습니다"));
            switch (parsed) {
                case Extraction.Unverified unverified -> {
                    return unverified;
                }
                case Extraction.Changes changes -> all.addAll(changes.changes());
            }
        }
        return new Extraction.Changes(all);
    }

    /**
     * 그 시도가 던진 작업 목록을 dispatch 응답에서 읽는다. plan 단계가 여러 job을 던졌다면 그 전부의 로그가
     * 있어야 하는데 — 하나라도 빠지면 그 job이 만들거나 지울 리소스가 요약에서 통째로 사라진다 — "전부"의
     * 기준을 어디서 가져오느냐가 이 검사의 전부다.
     *
     * 기준은 반드시 dispatch 응답이어야 한다. 이 값은 태스크를 실행 중으로 옮기는 트랜잭션에서 함께
     * 저장되므로 그 시도가 실제로 던진 목록 그대로다. 반면 폴 관찰 테이블은 저장 실패를 삼키는 것이 계약이라,
     * 어떤 job의 관찰이 통째로 빠지면 그 job이 있었다는 사실 자체가 사라진다 — 그것을 기준으로 삼으면 로그가
     * 없는 job을 "애초에 없던 job"으로 착각해 반쪽짜리 요약이 검증됨으로 나간다.
     *
     * 읽지 못하면 빈 목록을 돌려주고, 호출자는 그것을 검증 불가로 처리한다. 목록을 모르면 로그가 전부인지
     * 판단할 근거가 없다는 뜻이므로, 넘겨짚는 대신 못 읽었다고 말한다.
     */
    private List<String> dispatchedJobIds(Long planTaskId, int attemptNumber) {
        return taskAttempts.findByTaskIdAndAttemptNumber(planTaskId, attemptNumber)
                .map(TaskAttempt::getResponse)
                .filter(response -> !response.isBlank())
                .map(response -> parseJobIds(planTaskId, attemptNumber, response))
                .orElseGet(List::of);
    }

    private static List<String> parseJobIds(Long planTaskId, int attemptNumber, String response) {
        try {
            return TerraformDispatchResponse.jobIds(response);
        } catch (JsonProcessingException malformed) {
            log.warn("task {} attempt {}: dispatch response is not a job id list — summary unverified",
                    planTaskId, attemptNumber, malformed);
            return List.of();
        }
    }

    private static Extraction parseOne(TerraformResult planLog) {
        if (planLog.getResult() == null) {
            return new Extraction.Unverified("Plan 로그 본문이 비어 있습니다");
        }
        if (planLog.isTruncated()) {
            return new Extraction.Unverified("Plan 로그가 잘려 저장됐습니다");
        }
        TerraformPlan parsed = TerraformPlanParser.parse(planLog.getResult());
        if (!parsed.consistent()) {
            return new Extraction.Unverified("Plan 로그의 변경 목록과 합계가 맞지 않습니다");
        }
        return new Extraction.Changes(parsed.changes());
    }

    /**
     * 컬럼 한도에 맞을 때까지 주소 목록을 줄여 가며 직렬화한다. 마지막 단계는 목록을 비우므로 집계 수치와
     * 잘림 표식은 어떤 경우에도 남는다.
     */
    private String writeBounded(Extraction extraction) {
        if (extraction instanceof Extraction.Unverified unverified) {
            return writeUnverified(unverified.reason());
        }
        List<Change> changes = ((Extraction.Changes) extraction).changes();
        String json = null;
        for (int addressLimit : ADDRESS_LIMITS) {
            json = write(summaryOf(changes, addressLimit));
            if (json.getBytes(StandardCharsets.UTF_8).length <= TaskApproval.PLAN_SUMMARY_MAX_BYTES) {
                return json;
            }
        }
        return json;
    }

    private PlanSummary summaryOf(List<Change> changes, int addressLimit) {
        List<ChangeView> shown = changes.stream()
                // 위험 순(교체·삭제 우선)으로 세워 두고 앞에서 잘라, 목록이 줄어도 판단에 중요한 것이 남게 한다.
                .sorted(Comparator.comparing(Change::kind).thenComparing(Change::address))
                .limit(addressLimit)
                .map(change -> new ChangeView(change.address(), change.kind()))
                .toList();
        return PlanSummary.builder()
                .verified(true)
                .createCount(count(changes, TerraformChangeKind.CREATE))
                .updateCount(count(changes, TerraformChangeKind.UPDATE))
                .destroyCount(count(changes, TerraformChangeKind.DESTROY))
                .replaceCount(count(changes, TerraformChangeKind.REPLACE))
                .importCount(count(changes, TerraformChangeKind.IMPORT))
                .forgetCount(count(changes, TerraformChangeKind.FORGET))
                .moveCount(count(changes, TerraformChangeKind.MOVE))
                .changes(shown)
                .addressesTruncated(shown.size() < changes.size())
                .omittedCount(changes.size() - shown.size())
                .build();
    }

    private static long count(List<Change> changes, TerraformChangeKind kind) {
        return changes.stream().filter(change -> change.kind() == kind).count();
    }

    private String writeUnverified(String reason) {
        return write(PlanSummary.builder()
                .verified(false)
                .unverifiedReason(reason)
                .changes(List.of())
                .build());
    }

    private String write(PlanSummary summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException serializationFailure) {
            // 자기 record를 직렬화하지 못하는 것은 환경 문제가 아니라 버그다 — 삼키지 않고 드러낸다.
            throw new IllegalStateException("plan summary serialization failed", serializationFailure);
        }
    }
}
