# Task 재시작(Restart) — 백엔드 수정사항 명세

> 설계 원본: [pipeline-restart-design.md](pipeline-restart-design.md) (결정 1–5, §3–§4, §7)
> 이 문서는 **이 문서만 보고 구현 가능한** 착수용 명세다. 근거·대안 비교는 설계 문서를 본다.
> 코드 스니펫의 시그니처·이름은 현 코드베이스(브랜치 `claude/install-pipeline-restart-uhuc6e`)와 대조 완료.

## 0. 변경 요약

| 층 | 변경 | 엔진 영향 |
|---|---|---|
| 스키마 | `pipeline.origin_pipeline_id`, `task.origin_task_id` (write-once, nullable) + 인덱스 1개 | 없음 — 엔진은 두 컬럼을 읽지 않는다 |
| API | `GET .../restart-preview`, `POST .../restart` 신설 + 조회 DTO 확장 | 없음 |
| 서비스 | `PipelineRestarter` 신설, `PipelinePlan`/`PipelineInserter` 확장, `PipelineCreator` 2메서드 가시성 완화 | 없음 |
| 알림 | `NotifyPayload` 필드 1개 + `SCHEMA_VERSION` "2"→"3" | 알림 상태 기계 무변경 |
| ADR | ADR-016 스키마 절 소규모 개정 1건 | — |
| ADR-021 | **무변경** (claim/write-back/cancel 경로 무접촉) | — |

**전제**: `spring.jpa.hibernate.ddl-auto: update`(application.yml)이므로 스키마 반영은 엔티티
어노테이션 변경만으로 끝난다 — 마이그레이션 파일 없음.

## 1. 엔티티·스키마

### `Pipeline` (entity/Pipeline.java)

필드 추가(ADR-022 알림 메타데이터 블록 위, ADR-021 블록과 구분되는 자리에 둔다):

```java
/** 이 실행이 재시작한 원본 파이프라인 id. 재시작이 아니면 null. 표시용 계보 — 엔진은 읽지 않는다. */
@Column(name = "origin_pipeline_id", updatable = false)
private Long originPipelineId;
```

`@Table.indexes` 배열에 추가(역링크 조회 §7 지원):

```java
@Index(name = "idx_pipeline_origin", columnList = "origin_pipeline_id")
```

FK 제약은 걸지 않는다(설계 §4.2 — 원본 행 삭제 시에도 조회가 null-safe로 동작, 카탈로그 이름 열화와 같은 태도).

### `Task` (entity/Task.java)

```java
/** 이 task가 다시 실행하는 원본 task 행 id. 재시작이 아니면 null. 표시용 계보 — 엔진은 읽지 않는다. */
@Column(name = "origin_task_id", updatable = false)
private Long originTaskId;
```

## 2. Plan·삽입 경로

### `PipelinePlan` (model/PipelinePlan.java)

레코드 컴포넌트 `Long originPipelineId`를 `steps` 앞에 추가한다. **기존 팩토리 2개의 내부 호출만
바뀌고 시그니처는 유지** — 외부 호출자는 전부 팩토리 경유라 파급 없음.

```java
public record PipelinePlan(String target, PipelineType type, CloudProvider provider, String recipeDefinition,
        Long originPipelineId, List<PlannedStep> steps) {

    // compact constructor의 기존 검증 무변경(originPipelineId는 nullable — 검증 없음)

    public static PipelinePlan fromCatalog(String target, RecipeDefinition recipe) {
        ...
        return new PipelinePlan(target, recipe.pipelineType(), recipe.provider(), recipe.name(), null, steps);
    }

    public static PipelinePlan custom(String target, CloudProvider provider, List<PlannedStep> steps) {
        return new PipelinePlan(target, PipelineType.CUSTOM, provider, null, null, steps);
    }

    /**
     * 재시작 plan — type/recipeDefinition을 원본에서 승계하고 계보(originPipelineId)를 실어 만든다(설계 결정 1·2).
     * provider는 호출자(PipelineRestarter)가 원본 저장값 또는 폴백 조회로 확정해 넘긴다.
     */
    public static PipelinePlan restartOf(Pipeline origin, CloudProvider provider, List<PlannedStep> steps) {
        Objects.requireNonNull(origin, "origin must not be null");
        return new PipelinePlan(origin.getTarget(), origin.getType(), provider,
                origin.getRecipeDefinition(), origin.getId(), steps);
    }
```

`PlannedStep`은 `originTaskId`를 추가하되 **2-인자 편의 생성자를 남겨** 기존 호출부
(`fromCatalog`의 `new PlannedStep(definition, null)`, `PipelineCreator.resolveNameAndDescription`)를
무변경으로 지킨다:

```java
    public record PlannedStep(TaskDefinition definition, String description, Long originTaskId) {
        public PlannedStep {
            Objects.requireNonNull(definition, "definition must not be null");
        }
        /** 기존 catalog/custom 경로용 — 계보 없음. */
        public PlannedStep(TaskDefinition definition, String description) {
            this(definition, description, null);
        }
    }
}
```

### `PipelineInserter` (service/lifecycle/PipelineInserter.java)

두 줄 추가, 그 외 무변경(첫 task READY·나머지 BLOCKED, sequence 0부터 재부여, startDelay 시딩 그대로):

- `insert`의 `Pipeline.builder()` 체인에 `.originPipelineId(plan.originPipelineId())`
- `buildChain`의 `Task.builder()` 체인에 `.originTaskId(step.originTaskId())`

### `PipelineCreator` (service/lifecycle/PipelineCreator.java) — 가시성 완화 2건

`PipelineRestarter`(같은 패키지)가 재사용하도록 아래 2개 메서드의 `private`을 제거해
**package-private**으로 만든다. 로직 무변경 — 중복 구현 금지:

```java
/** plan 삽입 + active-target 유일 위반의 도메인 번역. catalog/custom/restart 경로가 공유한다. */
Pipeline insert(PipelinePlan plan, String target) { ... }   // 기존 private 제거

/** cloud provider 조회(외부 호출) — 실패는 503 번역. create/restart(provider 열화 폴백)가 공유한다. */
CloudProvider resolveProvider(String target) { ... }        // 기존 private 제거
```

## 3. 신규 서비스 — `PipelineRestarter` (service/lifecycle/PipelineRestarter.java)

`PipelineCreator`와 같은 패턴: 클래스 수준 `@Transactional` 없음(읽기·검증은 트랜잭션 밖,
삽입만 inserter 트랜잭션). preview/restart는 아래 `compute()`를 **공유한다(분기 금지)** —
preview가 성공하면 restart도 (경합 제외) 성공한다는 계약의 근거다.

```java
@Service
@RequiredArgsConstructor
public class PipelineRestarter {

    private final PipelineRepository pipelines;
    private final TaskRepository tasks;
    private final PipelineCreator pipelineCreator;   // §2의 package-private insert/resolveProvider 재사용
    private final PipelineSettings pipelineSettings;
    private final Clock clock;

    public Pipeline restart(String target, Long pipelineId, Integer fromSequence) {
        RestartComputation c = compute(target, pipelineId, fromSequence);
        return pipelineCreator.insert(PipelinePlan.restartOf(c.origin(), c.provider(), c.steps()), target);
    }

    public RestartPreview preview(String target, Long pipelineId, Integer fromSequence) {
        RestartComputation c = compute(target, pipelineId, fromSequence);
        return RestartPreview.from(c, warnings(c.origin()));
    }

    /** 검증·suffix 계산 결과 — preview 렌더와 restart 삽입이 같은 값을 쓴다. */
    record RestartComputation(Pipeline origin, List<Task> originChain, int resumeFromSequence,
            CloudProvider provider, List<PlannedStep> steps) {
        List<Task> skipped()    { return originChain.stream().filter(t -> t.getSequence() <  resumeFromSequence).toList(); }
        List<Task> suffix()     { return originChain.stream().filter(t -> t.getSequence() >= resumeFromSequence).toList(); }
    }
}
```

`compute(target, pipelineId, fromSequence)` — 검증 순서와 예외(모두 이 순서대로):

```java
private RestartComputation compute(String target, Long pipelineId, Integer fromSequence) {
    // 1) 원본 로드 + target 소속 검증 — 불일치·부재 모두 404 (기존 PipelineNotFoundException(long) 재사용)
    Pipeline origin = pipelines.findById(pipelineId)
            .filter(p -> p.getTarget().equals(target))
            .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

    // 2) 결정 5 허용표: FAILED/CANCELLED만. DONE·RUNNING·PENDING → 409 PIPELINE_NOT_RESTARTABLE.
    //    DONE은 active_target 백스톱이 없어 이 검사가 유일 방어선이다.
    if (origin.getStatus() != PipelineStatus.FAILED && origin.getStatus() != PipelineStatus.CANCELLED) {
        throw new PipelineNotRestartableException(pipelineId, origin.getStatus());
    }
    // 2b) 방어 가드: type이 열화(null 해석)된 옛 행은 PipelinePlan이 type을 요구하므로 재시작 불가로 거절.
    //     (500으로 새지 않게 여기서 409로 못박는다 — 실사용상 도달 불가.)
    if (origin.getType() == null) {
        throw new PipelineNotRestartableException(pipelineId, origin.getStatus());
    }

    // 3) 최신 실행 검증 — best-effort(최종 동시성 심판은 insert의 유일 제약). 기존 질의 재사용.
    Pipeline latest = pipelines.findFirstByTargetOrderByCreatedAtDescIdDesc(target).orElseThrow();
    if (!latest.getId().equals(origin.getId())) {
        throw new PipelineNotLatestException(pipelineId, latest.getId());
    }

    // 4) 기본 재시작 지점 = 첫 non-DONE task의 sequence (FAILED엔 FAILED task, CANCELLED엔 취소 task가
    //    반드시 존재 — orElseThrow는 방어용)
    List<Task> chain = tasks.findByPipelineIdOrderBySequenceAsc(origin.getId());
    int defaultResume = chain.stream().filter(t -> t.getStatus() != TaskStatus.DONE)
            .findFirst().map(Task::getSequence)
            .orElseThrow(() -> new PipelineNotRestartableException(pipelineId, origin.getStatus()));

    // 5) from_sequence 오버라이드는 "더 앞으로"만(결정 3): 0 <= fromSequence <= defaultResume
    int resume = fromSequence == null ? defaultResume : fromSequence;
    if (resume < 0 || resume > defaultResume) {
        throw new InvalidResumeSequenceException(resume, defaultResume);
    }

    // 6) suffix 재해석 — 사라진 task_definition은 조용한 열화 금지, 400 (기존 UnknownTaskException 재사용)
    List<PlannedStep> steps = chain.stream().filter(t -> t.getSequence() >= resume)
            .map(t -> new PlannedStep(
                    TaskDefinition.find(t.getTaskDefinition())
                            .orElseThrow(() -> new UnknownTaskException(t.getTaskDefinition())),
                    t.getDescription(),   // 원본이 CUSTOM이었으면 운영자 설명 승계(설계 §4.1)
                    t.getId()))
            .toList();

    // 7) provider: 원본 저장값 재사용, 열화(null)면 create와 동일 폴백(실패 → 503 ProviderLookupException)
    CloudProvider provider = origin.getCloudProvider() != null
            ? origin.getCloudProvider() : pipelineCreator.resolveProvider(target);

    return new RestartComputation(origin, chain, resume, provider, steps);
}
```

`warnings(origin)` — 차단 아닌 안내 1종(설계 §3.1): `origin.getLastActivityAt()`이
`clock.instant().minus(pipelineSettings.executionTimeout())` 이후면
`"원본 실행이 최근에 종료되었습니다. 이전에 dispatch된 Terraform job이 아직 실행 중일 수 있습니다(멱등이므로 무해)."`
한 건을 담은 리스트, 아니면 빈 리스트.

## 4. 컨트롤러 — `TargetSourcePipelineController`

`PipelineRestarter` 주입 추가 후 메서드 2개:

```java
@GetMapping("/{pipelineId}/restart-preview")
public RestartPreview restartPreview(@PathVariable String targetSourceId, @PathVariable Long pipelineId,
        @RequestParam(name = "from_sequence", required = false) Integer fromSequence) {
    return pipelineRestarter.preview(targetSourceId, pipelineId, fromSequence);
}

@PostMapping("/{pipelineId}/restart")
public PipelineDetail restart(@PathVariable String targetSourceId, @PathVariable Long pipelineId,
        @RequestBody(required = false) RestartPipelineRequest request) {
    return queryService.toDetail(pipelineRestarter.restart(targetSourceId, pipelineId,
            request == null ? null : request.fromSequence()));
}
```

오류는 전부 `OrchestrationException` 서브타입이라 `GlobalAdvice` 무변경.

## 5. DTO (dto/pipeline/)

### 신규 — `RestartPipelineRequest`

```java
public record RestartPipelineRequest(@JsonProperty("from_sequence") Integer fromSequence) {
}
```

### 신규 — `RestartPreview` (설계 §3.1 JSON 형상 그대로)

```java
@Builder
public record RestartPreview(
        @JsonProperty("origin") OriginSummary origin,
        @JsonProperty("resume_from_sequence") int resumeFromSequence,
        @JsonProperty("skipped_tasks") List<SkippedTask> skippedTasks,
        @JsonProperty("tasks_to_run") List<TaskToRun> tasksToRun,
        @JsonProperty("warnings") List<String> warnings) {

    public record OriginSummary(
            @JsonProperty("pipeline_id") long pipelineId,
            @JsonProperty("type") PipelineType type,
            @JsonProperty("recipe_definition") String recipeDefinition,
            @JsonProperty("status") PipelineStatus status,
            @JsonProperty("total_task_count") long totalTaskCount,
            @JsonProperty("done_task_count") long doneTaskCount) {
    }

    public record SkippedTask(
            @JsonProperty("sequence") int sequence,
            @JsonProperty("task_definition") String taskDefinition,
            @JsonProperty("status") TaskStatus status) {
    }

    public record TaskToRun(
            @JsonProperty("sequence") int sequence,                       // 원본 체인에서의 sequence
            @JsonProperty("task_definition") String taskDefinition,
            @JsonProperty("kind") String kind,                            // 원본 task.taskName (TaskSummary.kind와 동일 파생)
            @JsonProperty("terraform_action") String terraformAction,     // operation.terraformAction().orElse(null)
            @JsonProperty("origin_task_id") long originTaskId,
            @JsonProperty("origin_status") TaskStatus originStatus,
            @JsonProperty("origin_error_code") ErrorCode originErrorCode,
            @JsonProperty("origin_fail_count") int originFailCount) {
    }

    /** RestartComputation → 응답 매핑. skipped/tasks_to_run은 원본 Task 행에서 그대로 투영한다. */
    static RestartPreview from(PipelineRestarter.RestartComputation c, List<String> warnings) { ... }
}
```

(`from`의 카운트: `total_task_count = originChain.size()`, `done_task_count = DONE 수` —
`PipelineQueryService.countDone`과 같은 기준.)

### 확장 — 기존 DTO 4종

| DTO | 추가 필드 | 채우는 곳 |
|---|---|---|
| `PipelineSummary` | `@JsonProperty("origin_pipeline_id") Long originPipelineId` | `from(...)`에 `pipeline.getOriginPipelineId()` 추가(positional record — 컴포넌트는 `lastActivityAt` 뒤에 붙이고, `from` 외의 위치 기반 생성 호출부가 테스트에 있으면 함께 수정) |
| `PipelineDetail` | `origin_pipeline_id`(Long), `origin`(`RestartOriginView`, 아래), `restarted_by_pipeline_id`(Long) | `@Builder`라 파급 없음 — `toDetail` §6에서 채움 |
| `TaskSummary` | `@JsonProperty("origin_task_id") Long originTaskId` | `from(task)`에 `.originTaskId(task.getOriginTaskId())` |
| `TaskDetail` | `@JsonProperty("origin_task_id") Long originTaskId` | `taskDetail(...)` 빌더 체인에 동일 추가 |

`PipelineDetail`의 origin 블록(신규 nested record 또는 별도 파일 `RestartOriginView`):

```java
public record RestartOriginView(
        @JsonProperty("pipeline_id") long pipelineId,
        @JsonProperty("type") PipelineType type,
        @JsonProperty("recipe_definition") String recipeDefinition,
        @JsonProperty("status") PipelineStatus status,
        @JsonProperty("total_task_count") long totalTaskCount,
        @JsonProperty("done_task_count") long doneTaskCount,
        @JsonProperty("resumed_from_sequence") Integer resumedFromSequence) {
}
```

## 6. 조회 채움 — `PipelineQueryService.toDetail`

`PipelineRepository`에 역링크 질의 추가(파생 쿼리, `idx_pipeline_origin`이 지원):

```java
/** 이 파이프라인을 재시작한 최신 실행(역링크). */
Optional<Pipeline> findFirstByOriginPipelineIdOrderByIdDesc(Long originPipelineId);
```

`toDetail` 빌더에 3필드 채움(클래스가 `readOnly` 트랜잭션이라 한 스냅샷):

- `originPipelineId(pipeline.getOriginPipelineId())`
- `restartedByPipelineId(pipelines.findFirstByOriginPipelineIdOrderByIdDesc(pipeline.getId()).map(Pipeline::getId).orElse(null))`
- `origin(...)`: `pipeline.getOriginPipelineId() == null`이면 null. 아니면
  `pipelines.findById(originId)`로 원본 로드(**부재 시 null — FK 없음, null-safe가 계약**),
  원본 체인 `tasks.findByPipelineIdOrderBySequenceAsc(originId)`로 total/done 계산.
  **`resumed_from_sequence` 산출식**: 이 파이프라인의 sequence-0 task의 `originTaskId`와
  id가 일치하는 원본 체인 행의 `sequence` (일치 행 없거나 originTaskId가 null이면 null).

목록(`PipelineSummary`) 경로는 자기 행 컬럼만 실으므로 추가 질의 없음 — 단건 detail에만
원본 단건+체인 조회 2회가 붙는다(허용: 상세 화면 1회성 호출).

## 7. 예외·오류 코드 (exception/)

`OrchestrationErrorCode`에 상수 3개 추가: `PIPELINE_NOT_RESTARTABLE`, `PIPELINE_NOT_LATEST`,
`INVALID_RESUME_SEQUENCE`. 서브타입 3개 신설(기존 `PipelineNotFoundException` 꼴):

```java
/** 결정 5 허용표 위반 — DONE(백스톱 없음)·RUNNING·PENDING(취소가 먼저) 원본. 409. */
public class PipelineNotRestartableException extends OrchestrationException {
    public PipelineNotRestartableException(long pipelineId, PipelineStatus status) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_NOT_RESTARTABLE,
                "pipeline " + pipelineId + " is " + status + " — only the latest FAILED/CANCELLED run can be restarted");
    }
}

/** 원본이 target의 최신 실행이 아님 — stale 이력 재시작 방지. 409. */
public class PipelineNotLatestException extends OrchestrationException {
    public PipelineNotLatestException(long pipelineId, long latestPipelineId) {
        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_NOT_LATEST,
                "pipeline " + pipelineId + " is not the latest run (latest: " + latestPipelineId + ")");
    }
}

/** from_sequence 범위 밖(음수 또는 기본 재시작 지점보다 뒤 — 실패 task 건너뛰기 금지). 400. */
public class InvalidResumeSequenceException extends OrchestrationException {
    public InvalidResumeSequenceException(int fromSequence, int defaultResumeSequence) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.INVALID_RESUME_SEQUENCE,
                "from_sequence " + fromSequence + " must be between 0 and " + defaultResumeSequence);
    }
}
```

(404 `PIPELINE_NOT_FOUND`, 400 `UNKNOWN_TASK`, 409 `PIPELINE_ALREADY_ACTIVE`, 503
`PROVIDER_LOOKUP_FAILED`는 기존 타입 재사용.)

## 8. ADR-022 알림 — `NotifyPayload` + `TerminalNotifier`

- `NotifyPayload`에 `Long originPipelineId` 컴포넌트 추가(허용 필드 — id 값이라 PII 아님).
- `SCHEMA_VERSION` `"2"` → `"3"`(수신측 형식 변경 감지 계약).
- `TerminalNotifier`의 `NotifyPayload.builder()` 채움부에 `.originPipelineId(pipeline.getOriginPipelineId())`.
- `NotifyPayloadPiiTest`의 허용 필드 검사에 새 필드 반영.

## 9. 테스트

신규 `src/test/java/com/bff/pipeline/service/RestartPipelineTest.java` —
`CustomRecipeCreationTest`와 동일 부트스트랩(`@DataJpaTest` + `Replace.NONE` +
`@Transactional(NOT_SUPPORTED)` + `@Import`에 `PipelineRestarter`·`PipelineCreator`·
`PipelineInserter`·`PipelineQueryService`·컨트롤러·`FakeInfraManagerClient` 배선 + `@AfterEach` 정리).
결정 5 거절 케이스가 필수다(특히 DONE — 유일 제약 백스톱이 없는 유일한 상태):

1. `failedPipelineRestartsFromFailedTask` — FAILED 원본(0·1 DONE, 2 FAILED, 3 CANCELLED) 재시작 →
   새 파이프라인: type/recipe/provider 승계, task 2개(원본 2·3), sequence 0부터, 첫 task READY,
   `origin_pipeline_id`/각 `origin_task_id` 스탬핑, fail_count 0
2. `cancelledPipelineRestartsFromCancelPoint` — CANCELLED 원본 → suffix = 취소 당시 진행 task부터
3. `doneOriginIsRejected` — **DONE 원본 → 409 `PIPELINE_NOT_RESTARTABLE`**
4. `liveOriginIsRejectedWithExplicitCode` — RUNNING·PENDING 원본 → 409 `PIPELINE_NOT_RESTARTABLE`
   (백스톱 `PIPELINE_ALREADY_ACTIVE`가 아니라 명시 코드가 먼저인지 확인)
5. `staleTerminalOriginIsRejected` — 같은 target에 terminal 실행 2건, 옛것 재시작 → 409 `PIPELINE_NOT_LATEST`
6. `activeRunBlocksRestart` — terminal 원본 + 그 뒤 새 활성 실행 존재 → 5의 NOT_LATEST가 먼저 걸림;
   최신 검증을 통과하는 경합 시나리오(검사 후 삽입 전 create 끼어듦)는 유일 제약 →
   409 `PIPELINE_ALREADY_ACTIVE` (insert 번역 재사용 확인 — FakeClock 불필요, 직접 활성 행 삽입으로 재현)
7. `unknownTaskDefinitionIsRejected` — suffix에 해석 불가 task_definition 행 → 400 `UNKNOWN_TASK`
8. `fromSequenceBounds` — 0(전체 재실행) 허용 / `defaultResume`와 동일 값 허용 /
   `defaultResume+1` 400 / 음수 400 (`INVALID_RESUME_SEQUENCE`)
9. `previewMatchesRestartAndSavesNothing` — preview: 1·3·4·5와 동일 검증으로 같은 예외 +
   성공 시 `resume_from_sequence`/`skipped_tasks`/`tasks_to_run`(origin_task_id·origin_status·
   origin_error_code·origin_fail_count) 형상 + pipeline 행 수 불변
10. `originDetailShowsReverseLink` — 재시작 후 원본 detail의 `restarted_by_pipeline_id` = 새 id,
    새 실행 detail의 `origin` 블록(total/done/resumed_from_sequence) 채워짐
11. `restartOfRestartChainsToImmediateOrigin` — 재시작의 재시작: `origin_pipeline_id`가 직전 실행을
    가리키고, 체인 중간(첫 원본) 재시작은 `PIPELINE_NOT_LATEST`

기존 테스트 파급: `PipelineSummary` 위치 기반 생성을 쓰는 테스트가 있으면 인자 1개 추가,
`NotifyPayloadPiiTest` 허용 필드 목록 갱신. 그 외 없음(플랜 팩토리 시그니처 유지).

## 10. 문서 후속

- ADR-016 스키마 절 개정: provenance 컬럼 2개 + "catalog type 체인은 `origin_pipeline_id`가
  있으면 recipe suffix일 수 있다" 1문장 (설계 §4.3).
- `docs/acceptance-criteria.md`에 재시작 수용 기준 추가(§9 테스트 목록 매핑).

## 11. 구현 순서 (설계 §7과 동일)

1. §1 스키마 + §2 plan/inserter/creator 가시성 (엔진 무접촉·회귀 위험 최소)
2. §3 `PipelineRestarter` + §7 예외 + §4 POST /restart + 테스트 1–8
3. GET /restart-preview + §5 `RestartPreview` + 테스트 9
4. §5 조회 DTO 확장 + §6 역링크/origin 채움 + §8 알림 + 테스트 10–11
5. §10 문서 후속

커밋 전 `recurring-review` 에이전트 실행(신규 빈 `PipelineRestarter` 추가·예외 경계 변경 해당).
