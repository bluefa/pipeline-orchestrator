# Pipeline REST API — 구현 계획 (orchestrator, `main@f504436` 기준)

관리자 대시보드용 pipeline 조회/명령 API를 **이 오케스트레이터의 인바운드 REST 레이어**로 신설한다.
요구사항([admin-pipeline-dashboard-requirements.md](admin-pipeline-dashboard-requirements.md))의 P1–P10을
현재 도메인(ADR-016) + 실행 모델(ADR-021, **이제 구현됨**)에 대조해 구현한다.

> ⚠️ 이전 feasibility 문서(PR #16)는 `d34d838` 기준이라 일부 낡음. 현재 main(`f504436`)에서는 ADR-021이
> 구현되어 `Pipeline`에 `cloudProvider/recipeDefinition/nextDueAt/claimedBy/claimedUntil/cancelRequested`가
> 존재한다. 이 계획이 정본이다.

> 🆕 **#15(`f504436`) 반영** — "bound CONDITION_CHECK by failCount (ttl → retry count), per-poll observation":
> - `PipelineSettings.timeToLive`·`Task.timeToLive`·`TaskSettingsResolver.resolveTimeToLive` **제거**. TTL은 더 이상
>   task 설정 축이 아니다. → **`TaskDetail`에서 `effectiveTimeToLive` 제거**, `executionTimeout`은 **TERRAFORM_JOB 전용**.
> - CONDITION_CHECK는 이제 **maxFailCount(재시도 예산)** 로 경계된다(TTL 아님). → P5의 `effectiveMaxFailCount`가 그 상한.
> - `ErrorCode.TIME_TO_LIVE_EXPIRED` → **`CONDITION_NOT_MET`** 개명. `CheckSignal`에 **`MET`** 추가.
>   (조회 API는 이 값들을 passthrough만 하므로 상수명을 직접 참조하지 않아 영향 없음.)
> - **per-poll observation**: CONDITION_CHECK는 폴 1회 = attempt 1개(각 `task_check.call_count=1`). 여전히
>   attempt당 check 0..1이라 `TaskAttemptView`/`TaskCheckView` 매핑은 그대로 유효하고, P5 attempts 목록만 폴 수만큼 길어진다.

## 0. 결정 사항

| 항목 | 결정 | 근거 |
|---|---|---|
| **경로 prefix** | `/api/v1` (BFF의 `/install/v1` 대체) | 오케스트레이터 자체 인바운드 API. 아웃바운드 Feign은 `/infra/*`, 이건 인바운드. BFF가 `/install/v1`로 프록시. |
| **JSON 네이밍** | **snake_case** (필드별 `@JsonProperty`) | BFF swagger 계약이 도메인 필드를 snake_case로 노출(`target_source_id`, `cloud_provider`, `service_code` 등). 전역 `spring.jackson` 전략 대신 **응답 DTO 필드마다 `@JsonProperty`** 로 명시 — 기존 Feign/공유 DTO 직렬화에 영향 없이 이 API에만 국한. |
| **DTO 패키지** | `dto/pipeline/` (신규) | 인바운드 응답 DTO를 아웃바운드 Feign DTO와 분리. `dto/`가 기존 관례. |
| **컨트롤러 패키지** | `controller/` (신규) | 계층 패키지 관례(`advice/`와 분리). 컨트롤러는 얇은 어댑터(SKILL §2). |
| **읽기 서비스** | `service/query/PipelineQueryService` | 조회/집계/파생은 읽기 전용 빈으로. 명령은 기존 lifecycle 재사용. |
| **target 표시명** | **미포함** | 다른 repo(target-source) 담당. 이 저장소는 `target`(id) + `cloudProvider`만 제공. |

## 1. 엔드포인트 (P1–P10)

| # | Method · Path | 재사용/신규 | 응답 |
|---|---|---|---|
| P1 | `GET /api/v1/pipelines/statistics/live` | 신규 조회 | `LivePipelineStatistics` |
| P2 | `GET /api/v1/pipelines/statistics?period=1h\|1d\|7d` | 신규 집계 | `PipelineStatistics` |
| P3 | `GET /api/v1/pipelines?status=&provider=&period=&page=&size=` | 신규 페이징 | `Page<PipelineSummary>` (BFF `PageServiceItem` 형태) |
| P4 | `GET /api/v1/pipelines/{pipelineId}` | 신규 조회+파생 | `PipelineDetail` |
| P5 | `GET /api/v1/pipelines/{pipelineId}/tasks/{taskId}` | 신규 조회 | `TaskDetail` |
| P6 | `POST /api/v1/pipelines/{pipelineId}/cancel` | **재사용** `PipelineControl.cancel` | `PipelineDetail` |
| P7 | `GET /api/v1/target-sources/{targetSourceId}/pipelines?page=&size=` | 신규 페이징 | `Page<PipelineSummary>` (BFF `PageServiceItem` 형태) |
| P8 | `GET /api/v1/target-sources/{targetSourceId}/pipelines/latest` | 신규 조회 | `PipelineSummary` \| 204 |
| P9 | `GET /api/v1/target-sources/{targetSourceId}/pipelines/preview?type=INSTALL\|DELETE` | **재사용** provider 조회+`RecipeCatalog` | `RecipePreview` |
| P10 | `POST /api/v1/target-sources/{targetSourceId}/pipelines` `{type}` | **재사용** `PipelineCreator.create` | `200 PipelineDetail` \| `409 ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`(이미 활성, ADR-016 §4) |

## 2. 응답 DTO (snake_case `@JsonProperty`, `dto/pipeline/`)

- **LivePipelineStatistics**: `runningPipelineCount`, `inProgressTerraformTaskCount`, `terraformSlotCap`, `runningPipelineCap`, `activeClaimCount`.
- **PipelineStatistics**: `period`, `since`, `runningCount`, `failedCount`, `doneCount`, `cancelledCount`, `totalCount`.
- **PipelineSummary**: `pipelineId`, `type`, `targetSourceId`, `cloudProvider`(nullable), `recipeDefinition`(nullable), `status`, `doneTaskCount`, `totalTaskCount`, `createdAt`, `lastActivityAt`.
- **PipelineDetail**: PipelineSummary 필드 + 실행 좌표(`nextDueAt`, `leased`(=claimedUntil>now), `cancelRequested`, `dueLagMillis`(nullable)) + 파생(`currentTaskSequence`(nullable, 최저 READY/IN_PROGRESS), `finalTaskSequence`, `currentFailCount`, `currentMaxFailCount`) + `tasks: List<TaskSummary>`. (claimedBy 토큰은 노출 안 함.)
- **TaskSummary**: `taskId`, `sequence`, `kind`(=taskName), `taskDefinition`(nullable), `operation`, `status`, `failCount`, `errorCode`(nullable), `consumesTerraformSlot`(nullable), `startedAt`, `finishedAt`.
- **TaskDetail**: task 컬럼 + 유효설정(`effectivePollingInterval`, `effectiveExecutionTimeout`(TERRAFORM_JOB 전용), `effectiveMaxFailCount` = `TaskSettingsResolver.resolve*`) + `nextCheckAt`, `readyAt` + `attempts: List<TaskAttemptView>`. (#15로 `timeToLive` 축 제거 — CONDITION_CHECK는 `maxFailCount`로 경계.)
- **TaskAttemptView**: `attemptNumber`, `status`, `errorCode`(nullable), `response`(nullable text), `startedAt`, `finishedAt`, `check: TaskCheckView`(nullable).
- **TaskCheckView**: `callCount`, `notMetCount`, `apiErrorCount`, `callTimeoutCount`, `lastExternalStatus`(nullable), `lastCheckedAt`(nullable). (lastResponseCode/Summary 제거됨 — 넣지 않음.)
- **RecipePreview**: `type`, `provider`, `recipeDefinition`(name), `displayName`, `description`, `steps: List<RecipePreviewStep>`.
- **RecipePreviewStep**: `sequence`, `taskDefinition`(name), `kind`(=mechanism), `operation`, `displayName`, `consumesTerraformSlot`.
- **CreatePipelineRequest**(요청): `type: PipelineType`.
- **목록(P3/P7)**: Spring Data `Page<PipelineSummary>`를 그대로 반환. BFF swagger의 `PageServiceItem`이 곧 이 Page 형태(`content/number/size/totalElements/totalPages/pageable/sort/numberOfElements/empty/first/last`)라 별도 래퍼 없이 컨벤션에 맞춘다. content 항목(PipelineSummary)은 snake_case, 페이지 봉투는 Spring 표준 필드명.

### 리뷰 반영 (codex round 1)
- **읽기 일관성**: `PipelineQueryService`는 클래스 전체 `@Transactional(readOnly = true)` — 여러 read를 한 스냅샷에서 읽어 "낡은 status + 새 집계" 조합을 막는다.
- **결정적 페이지네이션**: P3/P7은 기본 정렬 `createdAt DESC, id DESC`(`@PageableDefault`), P8 latest는 `OrderByCreatedAtDescIdDesc` tiebreaker.
- **kind별 설정**: `TaskDetail.effectiveExecutionTimeout`은 TERRAFORM_JOB일 때만 값, CONDITION_CHECK면 `null`(#15).
- **period 직렬화**: `StatisticsPeriod`에 `@JsonValue token()` — 응답이 enum 이름이 아니라 와이어 토큰(`1d`)으로 나간다.
- **N+1 제거**: P5 attempt별 check를 `findByTaskAttemptIdIn` 배치 로드(per-poll condition attempt 대비).
- **인덱스**: `Pipeline`에 `idx_pipeline_status_created`, `idx_pipeline_target_created` 추가(P2/P3/P7).

### BFF swagger 대조 (path · response format parity)
기존 BFF `install-v1.yaml` 컨벤션과 대조한 결과:
- **Path** ✅: `target-sources/{targetSourceId}/<subresource>` 중첩·kebab-case·POST 액션 서브리소스(`/cancel`)·`{targetSourceId}` 경로변수명이 BFF와 동일 스타일. prefix만 `/api/v1`(BFF는 `/install/v1`) — 무관하다고 확인받음.
- **필드 네이밍** ✅: BFF 도메인 필드가 snake_case(`service_code`, `target_source_id`, `cloud_provider`, `process_status`) → 응답 DTO 전부 `@JsonProperty`로 snake_case. `DtoSnakeCaseSerializationTest`로 고정.
- **페이지네이션** ✅: BFF `PageServiceItem` = Spring `Page` 형태 → 목록은 `Page`를 그대로 반환(항목은 snake_case).
- **enum** ✅: BFF는 enum을 대문자 문자열로 노출 → 이 API도 동일(`INSTALL`/`RUNNING`/`AWS`…), `StatisticsPeriod`만 토큰(`1d`).
- **에러 포맷** ✅: BFF `ErrorMessage = {timestamp, status, code, message, path}`에 맞춰 공유 `dto/ErrorResponse`를 5필드로 확장(`code` 유지). `GlobalAdvice`가 timestamp(주입 Clock), status(HttpStatus 문자열 "404 NOT_FOUND"), path(요청 URI)를 채운다. 이 변경은 오케스트레이터 **전역** 오류 응답에 적용된다(pipeline 외 포함) — BFF 계약 통일이 목적. `GlobalAdviceTest`로 고정.

## 3. 신규 repository 메서드

**PipelineRepository**
- `long countByStatus(PipelineStatus status)` — P1.
- `@Query select p.status,count(p) ... where p.createdAt>=:since group by p.status` → 투영 `PipelineStatusCount` — P2.
- `@Query Page<Pipeline> search(status, provider, since, Pageable)` (optional 필터: `(:x is null or ...)`, countQuery 명시) — P3.
- `Page<Pipeline> findByTargetOrderByCreatedAtDesc(String target, Pageable)` — P7.
- `Optional<Pipeline> findFirstByTargetOrderByCreatedAtDesc(String target)` — P8.

**TaskRepository**
- `@Query select t.pipelineId,t.status,count(t) ... where t.pipelineId in :ids group by t.pipelineId,t.status` → 투영 `PipelineTaskStatusCount` — P3/P7 진행 N/M 배치(요약 목록 N+1 회피).

(P4/P5는 기존 `findByPipelineIdOrderBySequenceAsc`, `findByTaskIdOrderByAttemptNumberAsc`, `findByTaskAttemptId` 재사용.)

## 4. 서비스

- **`service/query/PipelineQueryService`**(신규, 읽기 전용): `liveStatistics`, `statistics(period)`, `list(...)`, `detail(id)`, `taskDetail(pipelineId, taskId)`, `historyByTarget`, `latestByTarget`. `ExecutionSettings`·`PipelineSettings`·`Clock`·repos 주입.
- **`PipelineCreator`**(기존, 소폭 리팩터): `create`가 쓰는 provider 조회+recipe 선택을 private `resolveRecipe(target,type)`로 추출하고, 공개 `preview(target,type): RecipeDefinition` 추가(P9). create 동작은 불변.
- **재사용 그대로**: `PipelineControl.cancel`(P6), `PipelineCreator.create`(P10), `RecipeCatalog`.

## 5. 진행 N/M 규칙 (요구사항 Q5 확정)
분모 = 전체 task 수, 분자 = `status==DONE` 수. CANCELLED/BLOCKED/FAILED는 분자 제외. (취소된 pipeline은 status로 표시.)

## 6. 에러 처리 (SKILL §5.7 준수)
- 404: `PipelineNotFoundException`(기존, P4/P5/P6), 신규 `TaskNotFoundException`(P5 소유권 불일치 포함).
- 400: 잘못된 `period` → 신규 `InvalidStatisticsPeriodException`. 잘못된 enum 쿼리(status/provider/type 바인딩 실패) → `GlobalAdvice`에 `MethodArgumentTypeMismatchException` 핸들러 1개 추가 → 400 `ORCHESTRATION_INVALID_PARAMETER`(신규 `OrchestrationErrorCode` 값).
- P9 provider 조회 실패 503, 미지원 recipe 400 → 기존 `ProviderLookupException`/`UnsupportedRecipeException` 재사용.
- 컨트롤러는 얇게: 파싱·검증 후 서비스 위임, 예외는 서비스에서 typed로.

## 7. 테스트 (SKILL §4: `@Transactional` 금지, `@DataJpaTest`+`NOT_SUPPORTED`, 고정 Clock, fakes)
- `PipelineQueryServiceTest`: liveStatistics(카운트/슬롯/캡), statistics(period 경계), list(status/provider/period 필터 + 진행 N/M), detail(leased/cancelRequested/current·final task 파생), taskDetail(소유권 404), history/latest(정렬·204).
- `PipelineCreator.preview` 케이스는 기존 창작 테스트 패턴(FakeInfraManagerClient) 재사용.
- 검증: `mvn test`.

## 8. 산출 파일 요약
신규: 컨트롤러 2, 쿼리서비스 1, DTO 11(+요청 1), enum 1(`StatisticsPeriod`), 예외 2, 투영 2, 테스트.
수정(소폭): `PipelineCreator`(preview 추가), `GlobalAdvice`+`OrchestrationErrorCode`(param 핸들러).
