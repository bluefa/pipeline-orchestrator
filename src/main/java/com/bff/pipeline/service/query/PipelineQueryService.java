package com.bff.pipeline.service.query;

import com.bff.pipeline.config.ExecutionSettings;
import com.bff.pipeline.config.PipelineSettings;
import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.PipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineSummary;
import com.bff.pipeline.dto.pipeline.TaskAttemptView;
import com.bff.pipeline.dto.pipeline.TaskDefinitionView;
import com.bff.pipeline.dto.pipeline.TaskDetail;
import com.bff.pipeline.dto.pipeline.TaskSummary;
import com.bff.pipeline.dto.pipeline.TerraformResultDetail;
import com.bff.pipeline.dto.pipeline.TerraformResultSummary;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.StatisticsPeriod;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.exception.PipelineNotFoundException;
import com.bff.pipeline.exception.TaskNotFoundException;
import com.bff.pipeline.exception.TerraformResultNotFoundException;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.PipelineStatusCount;
import com.bff.pipeline.repository.PipelineTaskStatusCount;
import com.bff.pipeline.repository.TaskAttemptRepository;
import com.bff.pipeline.repository.TaskCheckRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.repository.TerraformResultMetadata;
import com.bff.pipeline.repository.TerraformResultRepository;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskCheck;
import com.bff.pipeline.utils.TaskSettingsResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 대시보드용 pipeline 조회/집계/파생을 담는 읽기 전용 서비스다(P1~P8). 실행 경로(claim/write-back)와는
 * 무관하며 상태를 바꾸지 않는다 — 명령(cancel/create)은 lifecycle 서비스가 담당하고, 그 결과 Pipeline을
 * {@link #toDetail}로 상세 응답으로 바꿔 재사용한다.
 *
 * <p>현재 task는 ADR-016 정의대로 최저 순번의 READY/IN_PROGRESS task이며 없으면 null이다. 진행 N/M은
 * 분모 = 전체 task 수, 분자 = DONE 수로 센다(CANCELLED/BLOCKED/FAILED는 분자 제외). 목록의 진행 집계는
 * 페이지 단위 배치 질의로 계산해 행마다 task를 다시 읽는 N+1을 피한다. leased/dueLagMillis는 주입된 Clock 기준이다.
 *
 * <p>메서드마다 여러 read를 엮으므로(예: pipeline + 그 task 체인), 클래스 전체를 {@code readOnly} 트랜잭션으로
 * 묶어 한 스냅샷에서 읽는다 — 동시 write-back이 중간에 끼어들어 "낡은 pipeline 상태 + 새 task 집계" 같은 불가능한
 * 조합이 나오지 않게 한다. 명령(cancel/create)은 자신의 트랜잭션에서 커밋한 뒤, {@link #toDetail}가 새 read 트랜잭션에서
 * 커밋된 결과를 읽는다.
 */
@Service
@Transactional(readOnly = true)
public class PipelineQueryService {

    /** 목록 진행 N/M 집계값 — done = DONE 수, total = 전체 task 수. */
    private record TaskProgressCount(long done, long total) {
        static final TaskProgressCount NONE = new TaskProgressCount(0, 0);
    }

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final TaskCheckRepository checks;
    private final TerraformResultRepository terraformResults;
    private final ExecutionSettings executionSettings;
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public PipelineQueryService(PipelineRepository pipelines, TaskRepository tasks,
            TaskAttemptRepository attempts, TaskCheckRepository checks, TerraformResultRepository terraformResults,
            ExecutionSettings executionSettings, PipelineSettings pipelineSettings, Clock clock) {
        this.pipelines = pipelines;
        this.tasks = tasks;
        this.attempts = attempts;
        this.checks = checks;
        this.terraformResults = terraformResults;
        this.executionSettings = executionSettings;
        this.pipelineSettings = pipelineSettings;
        this.clock = clock;
    }

    public LivePipelineStatistics liveStatistics() {
        return LivePipelineStatistics.builder()
                .runningPipelineCount(pipelines.countByStatus(PipelineStatus.RUNNING))
                .pendingPipelineCount(pipelines.countByStatus(PipelineStatus.PENDING))
                .inProgressTerraformTaskCount(tasks.countByConsumesTerraformSlotIsTrueAndStatus(TaskStatus.IN_PROGRESS))
                .terraformSlotCap(executionSettings.terraformSlotCap())
                .runningPipelineCap(executionSettings.runningPipelineCap())
                .activeClaimCount(pipelines.countByClaimedUntilAfter(clock.instant()))
                .build();
    }

    public PipelineStatistics statistics(StatisticsPeriod period) {
        Instant since = clock.instant().minus(period.window());
        Map<PipelineStatus, Long> byStatus = new EnumMap<>(PipelineStatus.class);
        for (PipelineStatusCount row : pipelines.countByStatusSince(since)) {
            byStatus.put(row.getStatus(), row.getCount());
        }
        long pending = byStatus.getOrDefault(PipelineStatus.PENDING, 0L);
        long running = byStatus.getOrDefault(PipelineStatus.RUNNING, 0L);
        long failed = byStatus.getOrDefault(PipelineStatus.FAILED, 0L);
        long done = byStatus.getOrDefault(PipelineStatus.DONE, 0L);
        long cancelled = byStatus.getOrDefault(PipelineStatus.CANCELLED, 0L);
        return PipelineStatistics.builder()
                .period(period)
                .since(since)
                .pendingCount(pending)
                .runningCount(running)
                .failedCount(failed)
                .doneCount(done)
                .cancelledCount(cancelled)
                .totalCount(pending + running + failed + done + cancelled)
                .build();
    }

    public Page<PipelineSummary> list(PipelineStatus status, CloudProvider provider,
            StatisticsPeriod period, Pageable pageable) {
        Instant since = period == null ? null : clock.instant().minus(period.window());
        return summarize(pipelines.search(status, provider, since, pageable));
    }

    public Page<PipelineSummary> historyByTarget(String targetSourceId, Pageable pageable) {
        return summarize(pipelines.findByTarget(targetSourceId, pageable));
    }

    public Optional<PipelineSummary> latestByTarget(String targetSourceId) {
        return pipelines.findFirstByTargetOrderByCreatedAtDescIdDesc(targetSourceId).map(pipeline -> {
            List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
            return PipelineSummary.from(pipeline, countDone(chain), chain.size());
        });
    }

    public PipelineDetail detail(Long pipelineId) {
        Pipeline pipeline = pipelines.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
        return toDetail(pipeline);
    }

    /** 명령(cancel P6 / create P10) 결과 Pipeline을 상세 응답으로 바꾼다 — 컨트롤러가 재사용한다. */
    public PipelineDetail toDetail(Pipeline pipeline) {
        List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
        Instant now = clock.instant();
        Optional<Task> current = currentTask(chain);
        return PipelineDetail.builder()
                .pipelineId(pipeline.getId())
                .type(pipeline.getType())
                .targetSourceId(pipeline.getTarget())
                .cloudProvider(pipeline.getCloudProvider())
                .recipeDefinition(pipeline.getRecipeDefinition())
                .status(pipeline.getStatus())
                .createdAt(pipeline.getCreatedAt())
                .lastActivityAt(pipeline.getLastActivityAt())
                .nextDueAt(pipeline.getNextDueAt())
                .leased(isLeased(pipeline, now))
                .cancelRequested(pipeline.isCancelRequested())
                .dueLagMillis(dueLagMillis(pipeline, now))
                .currentTaskSequence(current.map(Task::getSequence).orElse(null))
                .finalTaskSequence(chain.isEmpty() ? null : chain.getLast().getSequence())
                .currentFailCount(current.map(Task::getFailCount).orElse(null))
                .currentMaxFailCount(current
                        .map(task -> TaskSettingsResolver.resolveMaxFailCount(task, pipelineSettings))
                        .orElse(null))
                .doneTaskCount(countDone(chain))
                .totalTaskCount(chain.size())
                .tasks(chain.stream().map(TaskSummary::from).toList())
                .build();
    }

    private static boolean isLeased(Pipeline pipeline, Instant now) {
        return pipeline.getClaimedUntil() != null && pipeline.getClaimedUntil().isAfter(now);
    }

    /** 스케줄링 지연(now - nextDueAt)은 RUNNING에만 의미가 있다 — 종료된 pipeline은 nextDueAt이 멈춰 있어 0으로 둔다. */
    private static long dueLagMillis(Pipeline pipeline, Instant now) {
        if (pipeline.getStatus().isTerminal()) {
            return 0L;
        }
        return Math.max(0L, Duration.between(pipeline.getNextDueAt(), now).toMillis());
    }

    public TaskDetail taskDetail(Long pipelineId, Long taskId) {
        if (!pipelines.existsById(pipelineId)) {
            throw new PipelineNotFoundException(pipelineId);
        }
        Task task = tasks.findById(taskId)
                .filter(candidate -> candidate.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new TaskNotFoundException(pipelineId, taskId));
        return TaskDetail.builder()
                .taskId(task.getId())
                .pipelineId(task.getPipelineId())
                .sequence(task.getSequence())
                .kind(task.getTaskName())
                .taskDefinition(task.getTaskDefinition())
                .definition(TaskDefinition.find(task.getTaskDefinition())
                        .map(TaskDefinitionView::from).orElse(null))
                .operation(task.getOperation())
                .status(task.getStatus())
                .failCount(task.getFailCount())
                .errorCode(task.getErrorCode())
                .consumesTerraformSlot(task.getConsumesTerraformSlot())
                .startedAt(task.getStartedAt())
                .readyAt(task.getReadyAt())
                .finishedAt(task.getFinishedAt())
                .nextCheckAt(task.getNextCheckAt())
                .effectivePollingInterval(TaskSettingsResolver.resolvePollingInterval(task, pipelineSettings))
                .effectiveExecutionTimeout(effectiveExecutionTimeout(task))
                .effectiveMaxFailCount(TaskSettingsResolver.resolveMaxFailCount(task, pipelineSettings))
                .attempts(attemptViews(taskId))
                .description(task.getDescription())
                .build();
    }

    /**
     * execution timeout은 TERRAFORM_JOB 전용이다(#15). CONDITION_CHECK는 maxFailCount로 경계되므로 null.
     * 미해석 operation(카탈로그에서 제거된 옛 값 → converter가 null로 열화)도 null로 둔다 — 표시용 파생이라
     * 조회를 터뜨리지 않는 쪽이 계약이다.
     */
    private Duration effectiveExecutionTimeout(Task task) {
        return task.getOperation() != null && task.getOperation().consumesTerraformSlot()
                ? TaskSettingsResolver.resolveExecutionTimeout(task, pipelineSettings)
                : null;
    }

    /**
     * 본문 전용 조회다(P11, 설계 §4.5) — task 상세의 result 메타에서 "로그 보기"가 lazy 호출한다. 소유권 체인
     * (pipeline 존재 → task 소속)을 검증한 뒤 유니크 키 (taskId, attemptNumber, jobId)의 행 하나만 읽는다.
     * 행 부재는 404, 본문 조회에 실패했던 포인터 행은 content = null인 200이다 — 두 상태는 안내가 다르다.
     */
    public TerraformResultDetail terraformResult(Long pipelineId, Long taskId, int attemptNumber, String jobId) {
        if (!pipelines.existsById(pipelineId)) {
            throw new PipelineNotFoundException(pipelineId);
        }
        tasks.findById(taskId)
                .filter(candidate -> candidate.getPipelineId().equals(pipelineId))
                .orElseThrow(() -> new TaskNotFoundException(pipelineId, taskId));
        return terraformResults.findByTaskIdAndAttemptNumberAndJobId(taskId, attemptNumber, jobId)
                .map(TerraformResultDetail::from)
                .orElseThrow(() -> new TerraformResultNotFoundException(taskId, attemptNumber, jobId));
    }

    /** attempt별 폴 요약을 한 번의 in 질의로 배치 로드해 매핑한다(per-poll condition attempt에서 N+1을 피한다). */
    private List<TaskAttemptView> attemptViews(Long taskId) {
        List<TaskAttempt> attemptList = attempts.findByTaskIdOrderByAttemptNumberAsc(taskId);
        Map<Long, TaskCheck> checkByAttemptId = checks
                .findByTaskAttemptIdIn(attemptList.stream().map(TaskAttempt::getId).toList()).stream()
                .collect(Collectors.toMap(TaskCheck::getTaskAttemptId, Function.identity()));
        Map<Integer, List<TerraformResultSummary>> resultsByAttemptNumber = terraformResultSummaries(taskId);
        return attemptList.stream()
                .map(attempt -> TaskAttemptView.from(attempt, checkByAttemptId.get(attempt.getId()),
                        resultsByAttemptNumber.getOrDefault(attempt.getAttemptNumber(), List.of())))
                .toList();
    }

    /** attempt 인라인용 result 메타를 attempt_number로 접는다 — 본문 없는 메타 투영이라 로그 I/O를 지불하지 않는다. */
    private Map<Integer, List<TerraformResultSummary>> terraformResultSummaries(Long taskId) {
        return terraformResults.findMetadataByTaskId(taskId).stream()
                .collect(Collectors.groupingBy(TerraformResultMetadata::getAttemptNumber,
                        Collectors.mapping(TerraformResultSummary::from, Collectors.toList())));
    }

    private Page<PipelineSummary> summarize(Page<Pipeline> page) {
        Map<Long, TaskProgressCount> counts = taskCounts(page.map(Pipeline::getId).getContent());
        return page.map(pipeline -> {
            TaskProgressCount progress = counts.getOrDefault(pipeline.getId(), TaskProgressCount.NONE);
            return PipelineSummary.from(pipeline, progress.done(), progress.total());
        });
    }

    /** (pipelineId, status)별 집계 행을 pipeline별 진행 N/M으로 접는다 — done = DONE 합, total = 전체 합. */
    private Map<Long, TaskProgressCount> taskCounts(List<Long> pipelineIds) {
        if (pipelineIds.isEmpty()) {
            return Map.of();
        }
        return tasks.countByPipelineIdInGroupByStatus(pipelineIds).stream()
                .collect(Collectors.groupingBy(PipelineTaskStatusCount::getPipelineId,
                        Collectors.teeing(
                                Collectors.filtering(row -> row.getStatus() == TaskStatus.DONE,
                                        Collectors.summingLong(PipelineTaskStatusCount::getCount)),
                                Collectors.summingLong(PipelineTaskStatusCount::getCount),
                                TaskProgressCount::new)));
    }

    private static Optional<Task> currentTask(List<Task> chain) {
        return chain.stream()   // 체인은 sequence 오름차순이라 첫 매칭이 최저 순번이다
                .filter(task -> task.getStatus() == TaskStatus.READY || task.getStatus() == TaskStatus.IN_PROGRESS)
                .findFirst();
    }

    private static long countDone(List<Task> chain) {
        return chain.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();
    }
}
