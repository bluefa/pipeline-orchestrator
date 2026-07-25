# Task 재시작(Restart) — 백엔드 수정사항 명세

> 설계 원본: [pipeline-restart-design.md](pipeline-restart-design.md) (결정 1–5, §3–§4, §7)
> 이 문서는 구현 착수용 **변경 파일·클래스 목록**이다. 근거·대안 비교는 설계 문서를 본다.

## 0. 변경 요약

| 층 | 변경 | 엔진 영향 |
|---|---|---|
| 스키마 | `pipeline.origin_pipeline_id`, `task.origin_task_id` (write-once, nullable) + 인덱스 1개 | 없음 — 엔진은 두 컬럼을 읽지 않는다 |
| API | `GET .../restart-preview`, `POST .../restart` 신설 + 조회 DTO 확장 | 없음 |
| 서비스 | `PipelineRestarter` 신설, `PipelinePlan`/`PipelineInserter` 확장 | 없음 |
| ADR | ADR-016 스키마 절 소규모 개정 1건 | — |
| ADR-021 | **무변경** (claim/write-back/cancel 경로 무접촉) | — |

## 1. 엔티티·스키마

### `Pipeline` (entity/Pipeline.java)

```java
/** 이 실행이 재시작한 원본 파이프라인 id. 재시작이 아니면 null. 표시용 계보 — 엔진은 읽지 않는다. */
@Column(name = "origin_pipeline_id", updatable = false)
private Long originPipelineId;
```

- `@Table.indexes`에 `@Index(name = "idx_pipeline_origin", columnList = "origin_pipeline_id")` 추가
  (역링크 조회 `restarted_by_pipeline_id` 지원).
- FK 제약은 걸지 않는다(설계 §4.2 — 카탈로그 이름 열화와 같은 태도로 null-safe 조회).

### `Task` (entity/Task.java)

```java
/** 이 task가 다시 실행하는 원본 task 행 id. 재시작이 아니면 null. 표시용 계보 — 엔진은 읽지 않는다. */
@Column(name = "origin_task_id", updatable = false)
private Long originTaskId;
```

## 2. Plan·삽입 경로

### `PipelinePlan` (model/PipelinePlan.java)

- 레코드 컴포넌트 추가: `Long originPipelineId` (catalog/custom 경로는 null).
- `PlannedStep`에 `Long originTaskId` 추가 (기존 경로는 null).
- 정적 팩토리 추가:

```java
/** 재시작 plan — type/provider/recipeDefinition을 원본에서 승계하고 계보를 실어 만든다(설계 결정 1·2). */
public static PipelinePlan restartOf(Pipeline origin, List<PlannedStep> steps)
```

- 기존 `fromCatalog`/`custom`은 시그니처 유지(내부에서 origin 필드 null 전달).

### `PipelineInserter` (service/lifecycle/PipelineInserter.java)

- `insert`: `originPipelineId(plan.originPipelineId())` 스탬핑 추가.
- `buildChain`: `originTaskId(step.originTaskId())` 스탬핑 추가.
- 그 외 로직 무변경 — 첫 task READY·나머지 BLOCKED, sequence 0부터 재부여, startDelay 시딩 그대로.

## 3. 신규 서비스 — `PipelineRestarter` (service/lifecycle/)

`PipelineCreator`와 같은 패턴: 클래스 수준 `@Transactional` 없음(읽기·검증은 트랜잭션 밖,
삽입만 inserter 트랜잭션), active-target 유일 위반의 도메인 번역 공유.

```
restart(target, pipelineId, fromSequence?):
  1) 원본 로드 + target 소속 검증            → 404 PipelineNotFoundException
  2) 결정 5 허용표 검증:
     - status가 FAILED/CANCELLED가 아니면    → 409 PipelineNotRestartableException
       (DONE·RUNNING·PENDING 모두 여기서 거절 — DONE은 백스톱이 없어 이 검사가 유일 방어선)
     - target의 최신 실행이 아니면           → 409 PipelineNotLatestException
  3) suffix 계산: 첫 non-DONE task부터 끝까지(sequence 오름차순)
     - fromSequence 오버라이드: 0 <= fromSequence <= 기본 지점, 아니면
       → 400 InvalidResumeSequenceException
  4) 각 task.task_definition을 TaskDefinition.find()로 재해석
     - 미해석                                → 400 UnknownTaskException (조용한 열화 금지)
  5) provider: 원본 cloud_provider 재사용, null(열화)이면 InfraManagerClient 조회 폴백
     (실패 → 503 ProviderLookupException — PipelineCreator.resolveProvider 재사용/추출)
  6) PipelinePlan.restartOf(origin, steps) → PipelineInserter.insert()
     - active-target 유일 위반               → 409 PipelineAlreadyActiveException (기존 번역)

preview(target, pipelineId, fromSequence?):
  1)~5)를 동일 수행(검증 실패는 preview부터 409/400 — 설계 §3.1)하고 저장 없이
  RestartPreview DTO를 반환. restart와 검증·suffix 계산 코드를 공유한다(분기 금지).
```

## 4. 컨트롤러·DTO

### `TargetSourcePipelineController`

```
GET  /api/v1/target-sources/{targetSourceId}/pipelines/{pipelineId}/restart-preview?from_sequence=
POST /api/v1/target-sources/{targetSourceId}/pipelines/{pipelineId}/restart      body: { from_sequence? }
```

- restart 응답은 기존 `queryService.toDetail(...)` 재사용(PipelineDetail).

### 신규/확장 DTO (dto/pipeline/)

| DTO | 변경 |
|---|---|
| `RestartPreview` (신규) | `origin`(요약 블록), `resume_from_sequence`, `skipped_tasks[]`, `tasks_to_run[]`(origin_task_id·origin_status·origin_error_code·origin_fail_count 포함), `warnings[]` — 설계 §3.1 JSON 형상 |
| `RestartPipelineRequest` (신규) | `from_sequence` (nullable) |
| `PipelineSummary` | `origin_pipeline_id` 추가 |
| `PipelineDetail` | `origin_pipeline_id`, `origin`(단건 조회로 채움), `restarted_by_pipeline_id`(역링크) 추가 |
| `TaskSummary` / `TaskDetail` | `origin_task_id` 추가 |
| `NotifyPayload` | `origin_pipeline_id` 추가(ADR-022 — 알림 문구 "재시작" 문맥) |

### 역링크 조회 (`PipelineQueryService` + `PipelineRepository`)

```java
/** 이 파이프라인을 재시작한 최신 실행(역링크). idx_pipeline_origin이 지원한다. */
Optional<Pipeline> findFirstByOriginPipelineIdOrderByIdDesc(Long originPipelineId);
```

- `toDetail`에서 `origin_pipeline_id != null`이면 원본 단건 조회로 `origin` 블록 구성,
  역링크는 위 질의로 `restarted_by_pipeline_id` 채움(둘 다 읽기 전용 트랜잭션 내).

## 5. 예외·오류 코드 (exception/)

`OrchestrationErrorCode`에 추가 + 각 `OrchestrationException` 서브타입 신설:

| 예외 | HTTP | 코드 |
|---|---|---|
| `PipelineNotRestartableException` | 409 | `PIPELINE_NOT_RESTARTABLE` |
| `PipelineNotLatestException` | 409 | `PIPELINE_NOT_LATEST` |
| `InvalidResumeSequenceException` | 400 | `INVALID_RESUME_SEQUENCE` |

(404 `PIPELINE_NOT_FOUND`, 400 `UNKNOWN_TASK`, 409 `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`,
503 provider 조회 실패는 기존 타입 재사용.)

## 6. 테스트 (통합 — 기존 `PipelineIntegrationTest`/`CustomRecipeCreationTest` 스타일)

결정 5 거절 케이스가 필수다(특히 DONE — 유일 제약 백스톱이 없는 유일한 상태):

1. FAILED 원본 재시작 → 새 파이프라인: type/recipe/provider 승계, suffix = 첫 non-DONE부터,
   sequence 0 재부여, `origin_pipeline_id`/`origin_task_id` 스탬핑, fail_count 0
2. CANCELLED 원본 재시작 → suffix = 취소 당시 진행 task부터
3. **DONE 원본 → 409 PIPELINE_NOT_RESTARTABLE**
4. **RUNNING/PENDING 원본 → 409 PIPELINE_NOT_RESTARTABLE** (명시 코드 — 백스톱 409보다 먼저)
5. 최신 아닌 terminal 원본 → 409 PIPELINE_NOT_LATEST
6. 활성 실행 존재(경합) → 409 ORCHESTRATION_PIPELINE_ALREADY_ACTIVE (insert 유일 제약)
7. 해석 불가 task_definition → 400 UNKNOWN_TASK
8. from_sequence 경계: 0(전체 재실행) 허용 / 기본 지점 초과 400 / 음수·범위 밖 400
9. restart-preview: 1·3·4·5와 동일 검증 + skipped/tasks_to_run/resume_from_sequence 형상
10. 역링크: 재시작 후 원본 detail의 `restarted_by_pipeline_id` = 새 id
11. 재시작의 재시작: origin 체인이 직전 실행을 가리키고, 최신 가드가 체인 끝만 허용

## 7. 문서 후속

- ADR-016 스키마 절 개정: provenance 컬럼 2개 + "catalog type 체인은 `origin_pipeline_id`가
  있으면 recipe suffix일 수 있다" 1문장 (설계 §4.3).
- `docs/acceptance-criteria.md`에 재시작 수용 기준 추가(§6 테스트 목록 매핑).

## 8. 구현 순서 (설계 §7과 동일)

1. §1 스키마 + §2 plan/inserter (엔진 무접촉·회귀 위험 최소)
2. §3 `PipelineRestarter` + §5 예외 + POST /restart + §6 테스트 1–8
3. GET /restart-preview + 테스트 9
4. §4 조회 확장(Summary/Detail/TaskSummary/역링크/NotifyPayload) + 테스트 10–11
5. §7 문서 후속
