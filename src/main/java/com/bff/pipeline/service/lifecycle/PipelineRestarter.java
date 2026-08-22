package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.pipeline.RestartPreview;
import com.bff.pipeline.dto.pipeline.RestartPreview.OriginSummary;
import com.bff.pipeline.dto.pipeline.RestartPreview.SkippedTask;
import com.bff.pipeline.dto.pipeline.RestartPreview.TaskToRun;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.InvalidResumeSequenceException;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.PipelineNotLatestException;
import com.bff.pipeline.exception.PipelineNotRestartableException;
import com.bff.pipeline.exception.UnknownTaskException;
import com.bff.pipeline.model.PipelinePlan;
import com.bff.pipeline.model.PipelinePlan.PlannedStep;
import com.bff.pipeline.model.RequestContext;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 실패한 파이프라인의 재시작을 구현한다(재시작 설계 결정 1–5). 재시작은 원본을 되살리는 것이 아니라
 * (ADR-016 §7 — terminal은 부활하지 않는다) 원본 체인에서 첫 non-DONE task부터의 suffix로 만드는
 * 새 파이프라인이다. type/recipe_definition은 원본에서 승계하고(CUSTOM으로 위장하지 않는다),
 * 계보는 write-once 컬럼(origin_pipeline_id/origin_task_id)으로만 남긴다 — 엔진(ADR-021)은 무변경.
 *
 * 재시작 가능 대상은 "target의 최신 실행 + FAILED/CANCELLED"뿐이다. DONE은 재시작할 실패 지점이
 * 없고 active_target 백스톱도 없어 여기의 명시 검사가 유일 방어선이며, RUNNING/PENDING은 취소가 먼저다.
 * 검사는 best-effort 읽기이고 동시 재시작·create 경합의 최종 심판은 insert의 active_target 유니크 제약이다.
 *
 * {@code @Transactional}을 일부러 붙이지 않는다(PipelineCreator와 동일) — 읽기·검증은 트랜잭션 밖에서
 * 끝내고 삽입만 inserter의 트랜잭션이며, 유니크 위반의 도메인 번역은 {@link PipelineCreator#insert}를 공유한다.
 * preview와 restart는 검증·suffix 계산({@link #compute})을 공유하므로, 미리보기가 성공하면 실행도
 * (경합이 없는 한) 성공한다.
 */
@Service
@RequiredArgsConstructor
public class PipelineRestarter {

    /** 재시작을 허용하는 원본 상태 — 미완으로 끝난 실행만(재시작 설계 결정 5). */
    private static final List<PipelineStatus> RESTARTABLE_STATUSES =
            List.of(PipelineStatus.FAILED, PipelineStatus.CANCELLED);

    private static final String IN_FLIGHT_JOB_WARNING =
            "원본 실행이 최근에 종료되었습니다. 이전에 dispatch된 Terraform job이 아직 실행 중일 수 있습니다(멱등이므로 무해).";

    private static final String GATE_PULLED_BACK_WARNING =
            "재시작 지점이 승인 단계라 바로 앞의 Plan 단계부터 다시 실행합니다. 승인자에게 보여줄 Plan 결과가"
                    + " 새 실행에 있어야 하기 때문입니다.";

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final PipelineCreator pipelineCreator;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    /**
     * 재시작 실행 — 검증·suffix 계산 후 새 파이프라인을 삽입한다. fromSequence는 선택적 오버라이드(더 앞으로만).
     * 요청 맥락은 요청이 실어 보낸 값을 쓰고, 비어 있으면 원본 실행에서 승계한다 — 승인 단계가 포함된 체인은
     * 요청자가 필수라, 승계 없이 두면 재시작이 400으로 막히거나 요청자 없는 승인 요청이 나간다.
     */
    public Pipeline restart(String target, Long pipelineId, Integer fromSequence, RequestContext request) {
        RestartComputation computation = compute(target, pipelineId, fromSequence);
        RequestContext inherited = request.orInheritFrom(computation.origin());
        return pipelineCreator.insert(PipelinePlan.restartOf(computation.origin(), computation.provider(),
                computation.steps(), requireRequesterIfGated(inherited, computation.steps())), target);
    }

    /** 새 체인에 승인 단계가 들어 있으면 요청자를 필수로 건다(승인 게이트 ADR §결정 4의 생성 규칙과 같은 조건). */
    private static RequestContext requireRequesterIfGated(RequestContext request, List<PlannedStep> steps) {
        boolean gated = steps.stream().anyMatch(step -> step.definition().isApprovalGate());
        return gated ? request.requireRequestedBy() : request;
    }

    /** 재시작 미리보기 — 실행과 동일한 검증을 수행하고(불가 상태는 여기서부터 409/400) 아무것도 저장하지 않는다. */
    public RestartPreview preview(String target, Long pipelineId, Integer fromSequence) {
        return toPreview(compute(target, pipelineId, fromSequence));
    }

    /** preview/restart가 공유하는 검증·suffix 계산. 분기 금지 — 두 경로의 결과가 항상 같은 근거다. */
    private RestartComputation compute(String target, Long pipelineId, Integer fromSequence) {
        Pipeline origin = loadRestartableOrigin(target, pipelineId);
        List<Task> originChain = tasks.findByPipelineIdOrderBySequenceAsc(origin.getId());
        int requestedResume = resolveResumeSequence(origin, originChain, fromSequence);
        int resumeFromSequence = pullBackPastApprovalGate(originChain, requestedResume);
        // suffix는 여기서 한 번만 계산하고 preview 렌더와 restart step이 같은 리스트를 쓴다(필터 중복 금지).
        List<Task> suffix = originChain.stream()
                .filter(task -> task.getSequence() >= resumeFromSequence)
                .toList();
        List<PlannedStep> steps = suffix.stream().map(PipelineRestarter::toStep).toList();
        // provider는 원본 저장값 재사용 — task들이 그 provider로 이미 검증된 상태다. 열화(null)면 create와 동일 폴백.
        CloudProvider provider = origin.getCloudProvider() != null
                ? origin.getCloudProvider()
                : pipelineCreator.resolveProvider(target);
        return RestartComputation.builder()
                .origin(origin)
                .originChain(originChain)
                .requestedResumeSequence(requestedResume)
                .resumeFromSequence(resumeFromSequence)
                .provider(provider)
                .suffix(suffix)
                .steps(steps)
                .build();
    }

    /**
     * 재시작 지점이 승인 단계면 그 앞까지 당긴다(승인 게이트 ADR §결정 3). 만료된 게이트는 첫 non-DONE
     * task라 기본 지점이 게이트 자신이 되는데, 그대로 두면 새 실행에 Plan 단계가 없어 승인자에게 보여줄
     * 결과가 존재하지 않는다 — 근거 없이 버튼만 보게 되는 셈이라 "승인만 다시 하기"는 허용하지 않는다.
     * 호출자가 지점을 직접 지정한 경우에도 같은 이유로 당긴다.
     */
    private static int pullBackPastApprovalGate(List<Task> originChain, int resume) {
        int adjusted = resume;
        while (adjusted > 0 && isApprovalGate(originChain, adjusted)) {
            adjusted--;
        }
        return adjusted;
    }

    private static boolean isApprovalGate(List<Task> originChain, int sequence) {
        return originChain.stream()
                .filter(task -> task.getSequence() == sequence)
                .anyMatch(task -> task.getOperation() != null && task.getOperation().isApprovalGate());
    }

    /** 원본 로드(404) + 결정 5 허용표(409) + 최신 실행 검증(409). */
    private Pipeline loadRestartableOrigin(String target, Long pipelineId) {
        Pipeline origin = pipelines.findById(pipelineId)
                .filter(pipeline -> pipeline.getTarget().equals(target))
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
        // type이 열화(미해석 옛 값 → null)된 행은 승계할 분류가 없다 — plan 구성에서 500으로 새기 전에 여기서 거절.
        if (!RESTARTABLE_STATUSES.contains(origin.getStatus()) || origin.getType() == null) {
            throw new PipelineNotRestartableException(pipelineId, origin.getStatus());
        }
        Pipeline latest = pipelines.findFirstByTargetOrderByCreatedAtDescIdDesc(target)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
        if (!latest.getId().equals(origin.getId())) {
            throw new PipelineNotLatestException(pipelineId, latest.getId());
        }
        return origin;
    }

    /**
     * 기본 재시작 지점 = 원본 체인의 첫 non-DONE task. FAILED엔 FAILED task가, CANCELLED엔 취소된 task가
     * 반드시 있어 빈 결과는 상태 기계상 도달 불가지만, 그래도 비면 재시작 불가로 거절한다(방어).
     * 오버라이드는 "더 앞으로"만 — 뒤로 가면 실패 task를 건너뛰어 설치 완전성이 깨진다(결정 3).
     */
    private static int resolveResumeSequence(Pipeline origin, List<Task> originChain, Integer fromSequence) {
        int defaultResume = originChain.stream()
                .filter(task -> task.getStatus() != TaskStatus.DONE)
                .findFirst().map(Task::getSequence)
                .orElseThrow(() -> new PipelineNotRestartableException(origin.getId(), origin.getStatus()));
        if (fromSequence == null) {
            return defaultResume;
        }
        if (fromSequence < 0 || fromSequence > defaultResume) {
            throw new InvalidResumeSequenceException(fromSequence, defaultResume);
        }
        return fromSequence;
    }

    /** 원본 task 행의 task_definition(진실원)을 재해석해 step으로 만든다 — 사라진 이름은 조용한 열화 대신 400. */
    private static PlannedStep toStep(Task task) {
        TaskDefinition definition = TaskDefinition.find(task.getTaskDefinition())
                .orElseThrow(() -> new UnknownTaskException(task.getTaskDefinition()));
        return new PlannedStep(definition, task.getDescription(), task.getId());
    }

    private RestartPreview toPreview(RestartComputation computation) {
        Pipeline origin = computation.origin();
        List<Task> originChain = computation.originChain();
        return RestartPreview.builder()
                .origin(OriginSummary.builder()
                        .pipelineId(origin.getId()).type(origin.getType())
                        .recipeDefinition(origin.getRecipeDefinition()).status(origin.getStatus())
                        .totalTaskCount(originChain.size()).doneTaskCount(countDone(originChain))
                        .build())
                .resumeFromSequence(computation.resumeFromSequence())
                .skippedTasks(computation.skipped().stream()
                        .map(task -> new SkippedTask(task.getSequence(), task.getTaskDefinition(), task.getStatus()))
                        .toList())
                .tasksToRun(computation.suffix().stream().map(PipelineRestarter::toTaskToRun).toList())
                .warnings(warnings(computation))
                .build();
    }

    private static TaskToRun toTaskToRun(Task task) {
        return TaskToRun.builder()
                .sequence(task.getSequence())
                .taskDefinition(task.getTaskDefinition())
                .kind(task.getTaskName())
                .terraformAction(task.getOperation() == null
                        ? null : task.getOperation().terraformAction().orElse(null))
                .originTaskId(task.getId())
                .originStatus(task.getStatus())
                .originErrorCode(task.getErrorCode())
                .originFailCount(task.getFailCount())
                .build();
    }

    /**
     * 차단이 아닌 안내(설계 §3.1). 원본이 executionTimeout 창 안에서 끝났으면 이전에 dispatch된 Terraform job이
     * 아직 InfraManager에서 돌고 있을 수 있다 — 멱등이라 무해하지만 운영자에게 알린다. 끝난 행은 갱신되지
     * 않으므로 lastActivityAt이 곧 끝난 시각이다.
     */
    private List<String> warnings(RestartComputation computation) {
        List<String> warnings = new ArrayList<>();
        Instant inFlightHorizon = clock.instant().minus(pipelineSettings.executionTimeout());
        if (computation.origin().getLastActivityAt().isAfter(inFlightHorizon)) {
            warnings.add(IN_FLIGHT_JOB_WARNING);
        }
        if (computation.resumeFromSequence() != computation.requestedResumeSequence()) {
            warnings.add(GATE_PULLED_BACK_WARNING);
        }
        return List.copyOf(warnings);
    }

    private static long countDone(List<Task> chain) {
        return chain.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();
    }

    /**
     * 검증·suffix 계산 결과 — preview 렌더와 restart 삽입이 같은 값을 쓴다(suffix/steps는 compute가 한 번만 만든다).
     * 순번 둘이 나란히 있어 위치 인자로 만들면 서로 바뀌어도 컴파일되고, 그러면 건너뛴 단계 계산과 게이트
     * 경고가 동시에 뒤집힌다 — 이름을 붙여 만든다.
     */
    @Builder
    private record RestartComputation(Pipeline origin, List<Task> originChain, int requestedResumeSequence,
            int resumeFromSequence, CloudProvider provider, List<Task> suffix, List<PlannedStep> steps) {

        List<Task> skipped() {
            return originChain.stream().filter(task -> task.getSequence() < resumeFromSequence).toList();
        }
    }
}
