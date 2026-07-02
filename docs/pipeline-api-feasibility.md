# Pipeline API — 구현 가능성 검토 & Response Schema 명세

**대상 저장소**: `pipeline-orchestrator` (ADR-016 install/delete pipeline **도메인 모델**).
**입력 요구사항**: [admin-pipeline-dashboard-requirements.md](admin-pipeline-dashboard-requirements.md) 의
`pipeline`으로 시작하는 신규 API **P1–P10 전부**.

이 문서는 요구사항의 각 API를 **이 저장소의 실제 엔티티/서비스에 대조**하여 (1) 구현 가능 여부,
(2) 구현 방법, (3) 정정된 Response Schema 를 명세한다. 스키마는 요구사항 원본이 아니라 **실제 코드가
뒷받침할 수 있는 필드**를 기준으로 한다.

---

## 0. 아키텍처 현실 — 요구사항 문서의 전제와 이 저장소의 차이

요구사항 문서는 **BFF 레이어**(`docs/swagger/install-v1.yaml` + `scripts/gen-api.mjs` → zod, Next.js
route/client)를 전제로 쓰였다. **이 저장소에는 그런 것이 없다.** 확인 결과:

| 요구사항 전제 | 이 저장소의 현실 |
|---|---|
| `docs/swagger/install-v1.yaml` (계약 정본) | **없음.** swagger/OpenAPI 파일 자체가 없다(`*.yaml`은 `application.yml`뿐). |
| `scripts/gen-api.mjs` → `lib/generated/install-v1.ts` (zod) | **없음.** Node/TS 코드젠 파이프라인 없음. Java/Maven 프로젝트. |
| `getUserServices` 등 ✅ 재사용 API | **이 저장소에 없음.** 별도 서비스(BFF/target-source)의 것. |
| REST 컨트롤러 | `controller/`에 `GlobalAdvice`(예외 핸들러) **하나뿐.** README: *"the REST layer added later."* |

**결론 — 두 레이어를 구분해야 한다.**
- 요구사항의 `/install/v1/...` 경로·snake_case·swagger는 **BFF(공개 API 레이어)** 의 계약이다.
- 그 데이터의 **원천은 이 오케스트레이터** 의 `pipeline`/`task`/`task_attempt`/`task_check` 테이블이다.

따라서 P1–P10의 "구현 가능성"은 결국 **이 저장소의 도메인 데이터가 각 응답 필드를 뒷받침하는가**의
문제이며, 물리적 구현체는 다음 중 하나다:

1. **오케스트레이터에 REST 읽기/명령 레이어 신설** (README가 말한 "added later" 레이어) — BFF가 이를 프록시.
2. **BFF가 오케스트레이터를 호출** — 단, BFF는 오케스트레이터에 없는 필드(예: ADR-021 lease)를 만들어낼 수 없다.

**이 문서는 (1) 오케스트레이터 REST 레이어 관점**으로 가능성을 평가한다(데이터 가용성은 레이어와 무관하며
여기서 결정된다). 필드 네이밍(와이어 snake_case vs Java camelCase·무약어)은 §5-Q6의 결정 사항이다.

### 실제 엔티티 = 스키마의 근거 (ground truth)

| 엔티티 | 파일 | 실제 필드 |
|---|---|---|
| `Pipeline` | `entity/Pipeline.java:41` | `id, type, target, status, createdAt, lastActivityAt, activeTarget` |
| `Task` | `entity/Task.java:55` | `id, pipelineId, sequence, taskName, operation, status, failCount, errorCode, startedAt, readyAt, finishedAt, nextCheckAt, timeToLive, pollingInterval, executionTimeout, maxFailCount, version` |
| `TaskAttempt` | `entity/TaskAttempt.java:45` | `id, taskId, attemptNumber, response, status, errorCode, startedAt, finishedAt` |
| `TaskCheck` | `entity/TaskCheck.java:39` | `id, taskAttemptId, callCount, notMetCount, apiErrorCount, callTimeoutCount, lastExternalStatus, lastResponseCode, lastResponseSummary, lastCheckedAt` |

---

## 1. 요구사항 스키마 vs 실제 필드 — 3대 불일치 (반드시 반영)

검토 중 발견한, 요구사항 문서의 스키마가 **이 저장소와 어긋나는** 지점. 정정하지 않으면 구현 불가.

### 불일치 A — `pipeline`의 ADR-021 실행 필드는 이 저장소에 **없다**
요구사항 도메인 표는 `pipeline.next_due_at, claimed_by, claimed_until, cancel_requested`를 나열하지만,
`Pipeline.java`에는 **존재하지 않는다**. AGENTS.md/README가 명시: 실행 모델(claim/lease/스케줄링/워커 풀)은
**ADR-021이며 이 저장소에서 의도적으로 제외**. `PipelineSettings.java`도 *"워커 풀 크기는 ADR-021 러너에
속하며 여기서 설정하지 않는다"* 고 못박음. 대신 `activeTarget`(유일성 강제용 내부 컬럼)만 있다.
→ **영향: P4의 `next_due_at`, `leased`, `cancel_requested`, `lag`, P1의 worker count.** 이 저장소만으로는 불가.

### 불일치 B — `task_attempt` 응답 모델이 **리팩터링됨** (요구사항 스키마가 stale)
요구사항은 `task_attempt.job_ids[], dispatch_response_code, dispatch_response_summary`를 나열하지만,
최근 리팩터링(ADR-016 ed97ec0, 커밋 `7a3e5af`/`75ceb83` "response-agnostic task seam")으로 이 필드들은
**제거**되고 단일 `response`(원시 외부 응답 **text**)로 대체되었다(`TaskAttempt.java:57` Javadoc:
*"엔진은 형식을 모른 채 저장만 하고, 각 task type이 완료 판정 시 자기 형식으로 역직렬화한다"*).
즉 Terraform job id는 이제 1급 컬럼이 아니라 `response` 텍스트 **안**에 있고, `TaskType`이 파싱한다.
→ **영향: P5의 `task_attempt` 스키마.** `job_ids/dispatch_response_*` → `response`(opaque) 로 정정.

### 불일치 C — `task.kind`는 enum이 아니라 `taskName`(String)
요구사항은 `task.kind(TERRAFORM_JOB/CONDITION_CHECK)`라 하지만, 실제로는 `taskName` **String**이며
`TaskTypeRegistry`가 이름으로 `TaskType`을 해석한다. 값 자체는 일치한다:
`TerraformTask.NAME = "TERRAFORM_JOB"`, `ConditionCheckTask.NAME = "CONDITION_CHECK"`
(`service/terraform/TerraformTask.java:47`, `service/ConditionCheckTask.java:27`).
→ **영향: P3/P4/P5의 `kind` 필드 = `taskName`.** 값은 그대로 쓰되 "enum 컬럼"이 아님에 유의(provider별
task 확장 시 새 이름 추가로 늘어남).

---

## 2. 재사용 가능한 기존 자산 (신규 작성 불필요)

| 요구 API | 이미 존재하는 서비스/메서드 | 파일 |
|---|---|---|
| **P10** INSTALL/DELETE 실행 | `PipelineCreator.create(target, type)` — **멱등**, 활성 run 있으면 그걸 반환(ADR-016 §4 유일성 그대로). 반환 타입은 `Pipeline` 하나뿐 — 신규/기존 구분 플래그 없음 | `service/PipelineCreator.java:42` |
| **P6** 취소 | `PipelineControl.cancel(pipelineId)` — 단일 트랜잭션 원자 취소, **멱등**(종료 상태면 그대로 반환), 없으면 `IllegalArgumentException` | `service/PipelineControl.java:41` |
| **P9** 실행 전 preview | `Recipes.forType(type)` — INSTALL=`[TERRAFORM_JOB/APPLY_NETWORK, CONDITION_CHECK/NETWORK_READY]`, DELETE=`[TERRAFORM_JOB/DESTROY_NETWORK]`. DB 접근 없는 순수 함수 | `service/Recipes.java:31` |

즉 **P6/P9/P10은 서비스 계층이 이미 존재**하고, 얇은 컨트롤러 + 요청/응답 DTO만 추가하면 된다.

---

## 3. API별 구현 가능성 & Response Schema (P1–P10 전부)

판정 범례: ✅ **지금 가능**(기존 자산 재사용) · ⚙️ **가능**(신규 repo 쿼리+DTO+컨트롤러 필요) ·
⚠️ **부분 가능**(일부 필드가 외부 서비스/ADR-021) · ❌ **이 저장소만으로 불가**.

각 스키마의 `// 원천` 주석: `Pipeline.status` = 엔티티 게터, *derived* = 계산, *external* =
target-source 서비스, *ADR-021* = 이 저장소에 없음.

---

### P1 · `GET /install/v1/pipelines/stats/live` — ⚠️ 부분 가능
실시간 순간값. **running count는 가능. slot 총량(리밋)은 ADR-021 소관.**

- **running count(= 사용 중 slot 수)** ✅: 신규 `long countByStatus(RUNNING)` (PipelineRepository).
- **총 slot 수 / 동시 수행 가능 pipeline 리밋** ❌: ("Worker 개수"의 정정된 의미.) 이는 **동시성 용량 =
  ADR-021 러너의 워커 풀 크기**와 같은 값이다. 이 저장소엔 **설정도 데이터도 없다** — `application.yml`의
  `pipeline.*`는 태스크 데드라인 4개뿐이고, 주석이 *"tick cadence·per-call timeout은 ADR-021 러너가 소유"*
  라고 명시(`PipelineSettings`도 동일). 이름을 slot으로 바꿔도 **원천은 그대로 ADR-021**. → 리밋 값은 ADR-021
  러너 설정/actuator에서 오고, BFF가 그것과 running count를 합쳐 표시(§5-Q1).
- **slot 사용률 프레이밍**: `사용 slot(N) = RUNNING count`(✅ 이 저장소) / `총 slot(M) = 리밋`(❌ ADR-021).
  즉 "3 / 50 slots" 에서 분자만 이 저장소가 제공.
- **in-progress TF task count** ⚙️(근사): `count(task WHERE taskName='TERRAFORM_JOB' AND status='IN_PROGRESS')`.
  ADR-021의 *명명된* metric은 아님(파생 근사치임을 UI에 명시).

```jsonc
{
  "running_pipeline_count": 12,      // ✅ countByStatus(RUNNING) = 사용 중 slot 수 (N)
  "in_progress_terraform_task_count": 8, // ⚙️ derived: count(task taskName=TERRAFORM_JOB, IN_PROGRESS) — 근사
  "total_slot_limit": null           // ❌ ADR-021 러너 용량(구 "worker 개수"). 이 저장소 불가 → 별도 소스/nullable
}
```

---

### P2 · `GET /install/v1/pipelines/stats?period=1h|1d|7d` — ⚙️ 가능(정의 확정 필요)
기간 내 status별 집계.

- **구현**: 신규 집계 쿼리 `select status, count(*) from pipeline where <col> >= :since group by status`.
- **정의 확정(§5-Q2)**: `<col>` = `created_at`(기간 내 시작된 run) 권장. `RUNNING`은 순간 상태이므로
  "기간 내 생성되었고 아직 RUNNING"으로 정의.

```jsonc
{
  "period": "1d",                    // 입력 에코
  "since": "2026-06-30T12:00:00Z",   // derived: now - period (집계 하한, created_at 기준)
  "running_count": 3,                // ✅ count(status=RUNNING & created_at>=since)
  "failed_count": 5,                 // ✅ count(status=FAILED  & created_at>=since)
  "done_count": 20                   // ✅ count(status=DONE    & created_at>=since)
}
```

---

### P3 · `GET /install/v1/pipelines?period=&status=&provider=&page=&size=` — ⚠️ 부분 가능
대시보드 목록. **pipeline 필드/페이징/status·period 필터는 가능. provider/표시명은 외부 조인.**

- **가능** ⚙️: `Page<Pipeline>` — 신규 `Pageable` 쿼리 + `status`/`created_at` 필터. Spring Data `Page` 컨벤션.
- **진행 N/M** ⚙️: task 집계 `count(status=DONE) / count(*)` per pipeline. N/M 계산 규칙은 §5-Q5.
- **범위 밖(다른 repo)** ⚠️: `target_name`, `cloud_provider`, `provider` **필터**는 **target-source repo 담당**
  (소유자 확정). 이 저장소는 `target`(=target_source_id)만 반환하고 조인/필드 채움을 하지 않는다. 호출측이
  그 repo에서 받아 합친다(§5-Q3).

```jsonc
// Page<PipelineSummary> (Spring Data Page 형태)
{
  "content": [{
    "pipeline_id": 101,              // ✅ Pipeline.id
    "type": "INSTALL",               // ✅ Pipeline.type
    "target_source_id": "ts-abc",    // ✅ Pipeline.target
    "target_name": null,             // ⚠️ external(target-source) — 이 저장소 미보유
    "cloud_provider": null,          // ⚠️ external(target-source) — 이 저장소 미보유
    "status": "RUNNING",             // ✅ Pipeline.status
    "done_task_count": 1,            // ⚙️ derived: count(task status=DONE)
    "total_task_count": 2,           // ⚙️ derived: count(task) — §5-Q5
    "created_at": "2026-07-01T09:00:00Z",       // ✅ Pipeline.createdAt
    "last_activity_at": "2026-07-01T09:05:00Z"  // ✅ Pipeline.lastActivityAt
  }],
  "number": 0, "size": 20, "total_elements": 42, "total_pages": 3  // ✅ Spring Page
}
```
> `provider` 필터를 지원하려면 목록 질의 전에 target-source에서 provider→target_source_id 목록을 받아
> `target IN (...)`로 거르는 방식이 현실적(§5-Q3).

---

### P4 · `GET /install/v1/pipelines/{pipelineId}` — ⚠️ 부분 가능
Pipeline 상세 + task 임베드. **메타/현재·최종 task/실패 한계치는 가능. ADR-021 필드는 불가(불일치 A).**

- **가능**: 메타(id/type/target/status/created/lastActivity) ✅, `tasks[]` 임베드 ✅
  (`findByPipelineIdOrderBySequenceAsc`, `TaskRepository`).
- **derived**: `current_task` — ADR-016 정의는 *"최저 sequence 의 `READY`/`IN_PROGRESS` task"* 이고, 현재
  구현은 *"최저 sequence 비종료(non-terminal) task"* 로 선택한다(정상 체인에선 동일; 앞선 task가 모두 DONE이므로).
  DTO 계산은 구현과 동일하게 하되, 비정상/전이 순간에 `BLOCKED`가 current로 잡히지 않도록 `READY`/`IN_PROGRESS`
  우선 규칙을 명시 권장 ✅,
  `final_task_sequence` = max sequence ✅, 현재 task `max_fail_count` = `TaskSettings.resolveMaxFailCount`
  (task override 없으면 전역 `PipelineSettings` 기본) ✅.
- **불가(불일치 A)** ❌: `next_due_at`, `leased`(claimed_until), `cancel_requested`, `lag`. 전부 ADR-021.
  → 스키마에서 **제거**하거나 ADR-021 도입 후로 미룸. 아래는 제거안(권장).

```jsonc
{
  "id": 101,                         // ✅
  "type": "INSTALL",                 // ✅
  "target_source_id": "ts-abc",      // ✅ Pipeline.target (+ external name/provider는 P3와 동일 정책)
  "status": "RUNNING",               // ✅
  "created_at": "2026-07-01T09:00:00Z",        // ✅
  "last_activity_at": "2026-07-01T09:05:00Z",  // ✅
  "current_task_sequence": 1,        // ⚙️ derived: 최저 sequence READY/IN_PROGRESS task (없으면 null)
  "final_task_sequence": 1,          // ⚙️ derived: max sequence
  "current_fail_count": 0,           // ⚙️ derived: 현재 task.failCount
  "current_max_fail_count": 3,       // ⚙️ derived: TaskSettings.resolveMaxFailCount(현재 task)
  "tasks": [                         // ✅ findByPipelineIdOrderBySequenceAsc
    { "id": 5001, "sequence": 0, "kind": "TERRAFORM_JOB",   // kind=taskName (불일치 C)
      "operation": "APPLY_NETWORK", "status": "DONE",
      "fail_count": 0, "error_code": null,
      "started_at": "...", "finished_at": "..." },
    { "id": 5002, "sequence": 1, "kind": "CONDITION_CHECK",
      "operation": "NETWORK_READY", "status": "IN_PROGRESS",
      "fail_count": 0, "error_code": null,
      "started_at": "...", "finished_at": null }
  ]
  // ❌ 제거됨 (ADR-021, 이 저장소 불가): next_due_at, leased, cancel_requested, lag
}
```

---

### P5 · `GET /install/v1/pipelines/{pipelineId}/tasks/{taskId}` — ✅ 가능 (스키마 정정 필수)
Task 상세 + attempts + check. **불일치 B 반영 시 완전 구현 가능.**

- **소유권 검증(필수)**: `{taskId}`만으로 조회하면 다른 pipeline의 task가 반환될 수 있다. 신규 repo 메서드
  `Optional<Task> findByIdAndPipelineId(Long id, Long pipelineId)` 를 쓰거나, `findById` 후
  `task.getPipelineId().equals(pipelineId)` 를 검증해 불일치 시 404 를 반환한다.
- **task 상세** ✅: 유효 설정값은 `TaskSettings.resolve*`(override→전역 기본)로 계산해 노출.
- **attempts** ✅: `findByTaskIdOrderByAttemptNumberAsc`(`TaskAttemptRepository`). **단 `job_ids/dispatch_response_*`
  → `response`(opaque text)** 로 정정(불일치 B). job id가 UI에 필요하면 `response`를 `TaskType`이 파싱해야 하는데,
  그 스키마는 task type의 사적 계약이므로 **범용 API가 파싱해선 안 됨** → 원문 `response` 노출을 권장.
- **check** ✅: attempt별 0..1건 `findByTaskAttemptId`(`TaskCheckRepository`). 단
  `last_response_code/summary`는 *"향후 HTTP 어댑터가 채울 필드"*(현재 대개 null, `TaskCheck.java:27` Javadoc).

```jsonc
{
  "task": {
    "id": 5002, "pipeline_id": 101, "sequence": 1,
    "kind": "CONDITION_CHECK",        // = taskName (불일치 C)
    "operation": "NETWORK_READY", "status": "IN_PROGRESS",
    "fail_count": 0, "error_code": null,
    "started_at": "...", "ready_at": "...", "finished_at": null,
    "next_check_at": "2026-07-01T09:06:00Z",
    // 유효 설정 (task override 없으면 전역 기본; TaskSettings.resolve*)
    "time_to_live_seconds": 1800, "polling_interval_seconds": 30, "execution_timeout_seconds": 600,
    "max_fail_count": 3
  },
  "attempts": [                        // ✅ findByTaskIdOrderByAttemptNumberAsc
    { "attempt_number": 1,             // attemptNumber (무약어; 요구사항 attempt_no)
      "status": "IN_PROGRESS", "error_code": null,
      "response": "{...}",             // ✅ 정정: 원시 외부 응답 text (job_ids/dispatch_response_* 폐기됨)
      "started_at": "...", "finished_at": null,
      "check": {                       // ✅ 이 attempt의 task_check (0..1)
        "call_count": 4, "not_met_count": 3, "api_error_count": 1, "call_timeout_count": 0,
        "last_external_status": "NOT_MET",
        "last_response_code": null,    // 현재 미채움(향후 HTTP 어댑터)
        "last_response_summary": null,
        "last_checked_at": "2026-07-01T09:05:30Z"
      }
    }
  ]
}
```

---

### P6 · `POST /install/v1/pipelines/{pipelineId}/cancel` — ✅ 지금 가능 (의미 단순화)
- **재사용**: `PipelineControl.cancel(pipelineId)`(`service/PipelineControl.java:41`). 단일 트랜잭션 원자
  취소, **멱등**(이미 종료면 그대로 반환), 없으면 `IllegalArgumentException`→`GlobalAdvice`가 4xx로 변환.
- **의미 차이**: 요구사항의 *"idle→즉시 / live→cooperative"* 는 **ADR-021** 개념이다. 이 저장소의 cancel은
  **항상 동기·즉시**(CANCELLING 중간 상태 없음, `PipelineControl` Javadoc). 즉 여기선 409/cooperative-latency가
  없다. `cancel_requested` 플래그도 없음(불일치 A). → 응답은 취소 후의 파이프라인 상태를 그대로 반환.

```jsonc
{ "id": 101, "status": "CANCELLED", "last_activity_at": "2026-07-01T09:07:00Z" }  // ✅ 취소 후 Pipeline
// 종료 상태에서 재취소: 기존 종료 상태를 그대로 200 반환(멱등). 없는 id: 4xx(GlobalAdvice).
```

---

### P7 · `GET /install/v1/target-sources/{targetSourceId}/pipelines?page=&size=` — ⚙️ 가능
대상 이력 목록.
- **구현**: 신규 `Page<Pipeline> findByTargetOrderByCreatedAtDesc(String target, Pageable)`.
- 응답은 **P3의 `PipelineSummary`와 동일** 스키마(외부 name/provider 정책 동일).

```jsonc
// Page<PipelineSummary> — P3 content 스키마와 동일
{ "content": [ /* PipelineSummary ... */ ], "number": 0, "size": 20, "total_elements": 7, "total_pages": 1 }
```

---

### P8 · `GET /install/v1/target-sources/{targetSourceId}/pipelines/latest` — ⚙️ 가능
최근 1건 카드(상태 무관).
- **구현**: 신규 `Optional<Pipeline> findFirstByTargetOrderByCreatedAtDesc(String target)`. 없을 때는
  **`200 OK` + `null` 바디** 또는 **`204 No Content`(바디 없음)** 중 하나로 정한다(204는 null 바디를 실을 수 없음 —
  둘을 섞지 말 것).

```jsonc
// 200 OK + PipelineSummary,  또는 없으면 204 No Content(바디 없음) — 아래는 200 케이스
{ "pipeline_id": 101, "type": "INSTALL", "target_source_id": "ts-abc",
  "status": "RUNNING", "done_task_count": 1, "total_task_count": 2,
  "created_at": "...", "last_activity_at": "..." }
```
> **최적화(선택)**: P7 첫 페이지(`page=0,size=N`) 첫 행을 latest로 재사용하면 P8 호출을 줄일 수 있다. 단
> P8은 요구사항에 **별개의 계약**으로 명시돼 있으므로, 생략이 아니라 "P7으로 충족 가능한 구현 최적화"로 볼 것
> — 별도 엔드포인트를 유지하든 P7로 대체하든 P8 응답 계약(최근 1건 or 204)은 그대로 만족해야 한다.

---

### P9 · `GET /install/v1/target-sources/{targetSourceId}/pipelines/preview?type=INSTALL|DELETE` — ✅ 지금 가능
실행 전 recipe(순서 task 목록) 미리보기.
- **재사용**: `Recipes.forType(type)`(`service/Recipes.java:31`). DB 접근 없음. `targetSourceId`는 경로 정합성/향후
  확장용(현재 recipe는 `(type)`만으로 결정; provider별 분기는 미래 확장).

```jsonc
{
  "type": "INSTALL",                  // 입력 에코
  "steps": [                          // ✅ Recipes.forType(INSTALL).steps()
    { "sequence": 0, "kind": "TERRAFORM_JOB",   "operation": "APPLY_NETWORK" },
    { "sequence": 1, "kind": "CONDITION_CHECK", "operation": "NETWORK_READY" }
  ]
}
// DELETE → steps: [ { "sequence": 0, "kind": "TERRAFORM_JOB", "operation": "DESTROY_NETWORK" } ]
```

---

### P10 · `POST /install/v1/target-sources/{targetSourceId}/pipelines` `{type}` — ✅ 지금 가능
INSTALL/DELETE 실행. 유일성(ADR-016 §4).
- **재사용**: `PipelineCreator.create(targetSourceId, type)`(`service/PipelineCreator.java:42`). **멱등** —
  비-종료 run이 이미 있으면 **에러가 아니라 그 run을 반환**(요구사항 규칙과 정확히 일치).
- **응답 스키마 확정**: `create`의 반환 타입은 `Pipeline` **하나뿐**이며 신규/기존을 구분하는 플래그가 없다.
  따라서 기본은 **항상 `200 OK` + `PipelineDetail`(P4 스키마)** 를 반환한다(요구사항의 "기존 run 반환(200)"과 일치).
  신규 생성만 `201`로 구분하려면 서비스가 그 여부를 알려줘야 하므로, `PipelineCreator`가 `record CreateOutcome(Pipeline
  pipeline, boolean created)` 같은 **명시적 결과 타입**을 반환하도록 바꿔야 한다(현재는 불가 → 별도 변경 필요).
  권장: v1은 항상 200으로 단순화.

```jsonc
// 요청
{ "type": "INSTALL" }               // PipelineType
// 응답: 항상 200 OK, PipelineDetail(P4)와 동일 스키마 (신규/기존 활성 run 모두 동일)
{ "id": 101, "type": "INSTALL", "target_source_id": "ts-abc", "status": "RUNNING",
  "created_at": "...", "last_activity_at": "...",
  "current_task_sequence": 0, "final_task_sequence": 1,
  "current_fail_count": 0, "current_max_fail_count": 3,
  "tasks": [ /* P4의 tasks[] 와 동일 */ ] }
```

---

## 4. 구현 방법 요약 — 추가해야 할 것 (오케스트레이터 REST 레이어)

| 계층 | 신규/재사용 | 항목 |
|---|---|---|
| `repository/` | 신규 쿼리 | `countByStatus`(P1), status·period 집계(P2), `Page` + 필터(P3), task count 집계(P3/P4), `findByTargetOrderByCreatedAtDesc`(P7), `findFirstByTargetOrderByCreatedAtDesc`(P8) |
| `service/` | 신규 읽기 빈 | `PipelineQueryService`(조회/집계/파생 계산). **명령은 재사용**: `PipelineControl.cancel`(P6), `PipelineCreator.create`(P10), `Recipes.forType`(P9) |
| `dto/` | 신규 응답 record | `PipelineSummary, PipelineDetail, TaskDetail, LivePipelineStatistics, PipelineStatistics, RecipePreview` — 무약어 준수(`Statistics` not `Stats`; §5-Q6) |
| `controller/` | 신규 | `PipelineController`(P1–P6), `TargetSourcePipelineController`(P7–P10). 현재 `GlobalAdvice`만 존재 → 이게 "added later" 레이어 |

**착수 난이도**: P6/P9/P10 = 얇은 컨트롤러만(서비스 존재). P2/P7/P8 = repo 쿼리+DTO. P3/P4/P5 = 파생 계산·집계
포함(중). P1 = worker 원천 결정 선행.

> **주의 — 범위 경계(소유자 확정)**: `target_name`/`cloud_provider`/`provider` 필터는 **다른 repo(target-source)
> 담당**이다. 이 저장소는 `target`(id)만 노출하며, 이를 위한 client·조인을 **추가하지 않는다**(도메인 순수 유지).
> 표시명/CSP는 호출측이 그 repo에서 받아 합친다.

---

## 5. 확정 필요 (Open Questions) — 요구사항 5건 + 검토 발견 2건

| # | 질문 | 이 검토의 권장/사실 |
|---|---|---|
| Q1 | slot 총량/TF 수치 원천 (P1) | "Worker 개수"는 **총 slot 수(동시 수행 pipeline 리밋)** 로 정정됨 = ADR-021 러너 용량. 이 저장소 **불가**(설정·데이터 없음) → ADR-021 설정/actuator에서. 이 저장소는 `RUNNING`(=사용 slot)만 제공. TF는 `IN_PROGRESS` 근사(명명 metric 아님). |
| Q2 | "기간 내 Running" 정의 (P2) | 집계 컬럼 = `created_at` 권장("기간 내 생성 & 현재 RUNNING"). |
| Q3 | 목록 target 조인 (P3/P7) | **해결 — 소유자 확정**: `target_name/cloud_provider` 및 `provider` 필터는 **다른 repo(target-source) 담당**이며 이 저장소 범위 밖. 이 저장소는 `target`(=target_source_id)만 반환하고, 표시명/CSP/필터는 호출측이 그 repo에 질의해 합친다. (provider 필터를 서버측에서 걸려면 그 repo에서 id 목록을 받아 `target IN (...)` 로 넘기면 됨.) |
| Q4 | "설치 상태" 정의 (3페이지) | 이 저장소 무관(target-source API 영역). `process_status`(라이프사이클)를 헤더 기본값으로. |
| Q5 | 진행 N/M 계산 | 제안: 분모 = 전체 task 수, 분자 = `status=DONE`. CANCELLED/BLOCKED는 분자 제외. 취소된 파이프라인은 "N/M(취소)"로 표기. **확정 필요.** |
| **Q6** | **필드 네이밍 규약** (검토 발견) | 와이어 snake_case(요구사항) vs Java DTO camelCase·**무약어**(AGENTS.md 규칙 6: `sequence` not `seq`, `attemptNumber` not `attempt_no`, `timeToLive` not `ttl`). Jackson `@JsonNaming`으로 snake_case 노출 가능. **규약 확정 필요.** |
| **Q7** | **cancel 의미** (검토 발견, 불일치 A) | 요구사항 "idle 즉시 / live cooperative(409)"는 ADR-021. 이 저장소 cancel은 **항상 동기·즉시·멱등**. UI의 cooperative-latency 전제는 이 저장소 단독에선 불필요. |

---

## 6. 종합 판정

| API | 판정 | 핵심 |
|---|---|---|
| P1 stats/live | ⚠️ 부분 | running(=사용 slot)✅ / 총 slot 리밋❌(ADR-021) / TF⚙️근사 |
| P2 stats?period | ⚙️ 가능 | 신규 집계, 기간 정의(Q2) |
| P3 목록 | ⚠️ 부분 | pipeline·페이징✅ / name·provider = 다른 repo 담당(범위 밖, Q3) |
| P4 상세 | ⚠️ 부분 | 메타·tasks✅ / next_due/lease/cancel_req/lag❌(ADR-021, 불일치 A) |
| P5 task 상세 | ✅ 가능 | **스키마 정정 필수**: attempt는 `response`(불일치 B) |
| P6 cancel | ✅ 지금 | `PipelineControl.cancel` 재사용, 의미 단순화(Q7) |
| P7 이력 목록 | ⚙️ 가능 | `findByTarget…` 신규 |
| P8 latest | ⚙️ 가능 | `findFirstByTarget…` 신규(또는 P7로 대체) |
| P9 preview | ✅ 지금 | `Recipes.forType` 재사용 |
| P10 실행 | ✅ 지금 | `PipelineCreator.create` 재사용(멱등·유일성 일치) |

**한 줄 결론**: **P6/P9/P10은 즉시(서비스 존재), P2/P5/P7/P8은 신규 repo 쿼리+DTO로 가능, P1/P3/P4는
ADR-021 필드·target-source 조인 때문에 부분 가능**이다. 구현 전 반드시 (a) 요구사항 스키마의 3대 불일치
(ADR-021 pipeline 필드, task_attempt `response` 리팩터링, `kind=taskName`) 정정과 (b) Open Questions
Q1·Q3·Q5·Q6·Q7 확정이 선행되어야 한다.
