# ADR-016 / ADR-021 ↔ 구현 정합성 검토 (2026-07-03)

PR #27(docs: PENDING 문구 이식) merge 직후, ADR 두 편의 서술을 `main`(e2afc6a) 구현 전체와
대조한 결과다. 각 항목에 ADR의 현재 서술, 구현의 실제, 권장 수정을 적었다.

**요약**: PENDING 상태 자체의 설계 서술(생성 분기, claim 시 전이, 취소 Case A/B 가드, 불변식 논증)은
구현과 정확히 일치한다. 어긋나는 곳은 크게 두 갈래다 — (1) **DB 물리 설계 서술이 MySQL 8 현실과 다름**
(partial index 2건: 하나는 PR #27이 새로 도입한 회귀), (2) **ADR-016 Schema/Enum 카탈로그가
이후 구현(PR #18/21/23)을 반영하지 못함** (active_target, task 컬럼 구성, terraform_result,
PipelineType.CUSTOM, ErrorCode.UNKNOWN_TASK 등).

| # | 문서 | 위치 | 내용 | 심각도 | 출처 |
|---|---|---|---|---|---|
| A-1 | ADR-016 | §4, Schema | per-target uniqueness가 "partial unique index"로 서술됨 — 실제는 `active_target` 컬럼 + UNIQUE 제약 | **높음** | 기존 + PR #27이 강화 |
| B-1 | ADR-021 | Safety §claim-predicate index | "partial btree index `(next_due_at) WHERE status IN (…)`" — MySQL 미지원, 실제는 `(status, next_due_at)` 복합 인덱스 | **높음** | **PR #27이 도입한 회귀** |
| A-2 | ADR-016 | Schema | `pipeline` 컬럼 목록에 `cloud_provider`, `recipe_definition`, `active_target` 누락 | 중간 | 기존 |
| A-3 | ADR-016 | Schema | `task` 컬럼 목록이 실제와 크게 다름 (`kind` 컬럼 없음 등) | 중간 | 기존 |
| A-4 | ADR-016 | §2, Enums | "Five core enums" / `TaskKind` enum은 존재하지 않고, `PipelineType`·`ErrorCode` 값이 늘어남 | 중간 | 기존 |
| A-5 | ADR-016 | Schema | `task_check`에 `last_response_code`, `last_response_summary` 컬럼 없음 | 중간 | 기존 |
| A-6 | ADR-016 | §3, Schema | 관찰 테이블이 둘이 아니라 셋 — `terraform_result`(postCheck) 미반영 | 중간 | 기존 |
| B-2 | ADR-021 | Decision 6 Case A SQL | 실제 취소는 `active_target = NULL`(uniqueness 슬롯 해제)과 `last_activity_at` 갱신을 함께 수행 | 중간 | 기존 |
| B-3 | ADR-021 | Decision 4 | tx2 종료 수렴도 `active_target` 해제를 포함 — ADR에 부재 | 중간 | 기존 |
| B-4 | ADR-021 | 전반 | SQL 스니펫이 PostgreSQL 문법 — 실제 타깃은 MySQL 8 + JPA(스키마는 Hibernate ddl-auto 생성, 마이그레이션 없음) | 낮음 | 기존 |
| B-5 | ADR-021 | Knobs | knob 이름 불일치(`slotCap`→`terraform-slot-cap` 등), 구현에만 있는 knob 누락 | 낮음 | 기존 |
| A-7 | ADR-016 | §2, Context | recipe가 "code default per (type, provider)"로만 서술 — CUSTOM(운영자 구성, LIN-18) 미반영 | 낮음 | 기존 |

---

## A. ADR-016 수정 필요 항목

### A-1. per-target uniqueness 메커니즘 — partial unique index가 아니라 `active_target` 컬럼 (높음)

- **ADR 서술** (`docs/adr/016-…md:196-201`, §4 `:108-109`도 동일 전제):
  > A **partial unique index on `target` over non-terminal `status`** (non-terminal = `PENDING`, `RUNNING`) enforces Decision 4's per-target uniqueness
- **구현**: MySQL 8은 partial(filtered) unique index를 지원하지 않는다. 실제 메커니즘은
  `pipeline.active_target` 컬럼 + 일반 UNIQUE 제약 `uq_pipeline_active_target`이다
  (`entity/Pipeline.java:26-43`).
  - 생성 시 `active_target = target`으로 채운다 (`PipelineInserter.java:61`) — status와 무관하게
    **생성 즉시** 슬롯을 점유하므로 "PENDING이 생성 순간부터 슬롯을 쥔다"는 ADR 문장 자체는 참이다.
  - 종료 시 NULL로 비운다 — tx2 수렴(`StepReporter.terminalize`, `StepReporter.java:128-132`)과
    Case A 취소(`PipelineRepository.cancelIfIdle`, `PipelineRepository.java:62-68`) 둘 다.
    MySQL UNIQUE는 NULL 중복을 허용하므로 종료 행끼리는 충돌하지 않는다.
  - 중복 create의 409 번역은 제약 **이름**으로 판별한다 (`PipelineCreator.java:156-168`).
- **권장 수정**: §4와 Schema의 "partial unique index on target over non-terminal status" 문구를
  "MySQL은 partial index를 지원하지 않으므로, 애플리케이션이 관리하는 `active_target` 컬럼
  (비종료 동안 `= target`, 종료 전이와 같은 트랜잭션에서 `NULL`)에 걸린 UNIQUE 제약이 불변식을
  강제한다"로 교체. "insert 경쟁에서 진 쪽이 409로 표면화된다"는 결론은 그대로 유효하다.
  `active_target`의 수명주기(생성 시 세팅, terminalize/cancel 시 해제)도 함께 명시할 것.

### A-2. `pipeline` 컬럼 목록 누락 (중간)

- **ADR 서술** (`:196`): `pipeline(id, type, target, status, created_at, last_activity_at)` + 실행 4컬럼.
- **구현** (`entity/Pipeline.java`): 위에 더해 도메인 컬럼 3개가 있다 —
  `cloud_provider`(생성 시 조회한 provider의 write-once 캐시), `recipe_definition`(이 실행을 만든
  RecipeDefinition 상수 이름 — Admin API 조인 링크), `active_target`(A-1의 uniqueness 컬럼).
- **권장 수정**: Schema의 pipeline 행에 세 컬럼 추가(각 한 줄 설명). `active_target`은 A-1과 연결.

### A-3. `task` 컬럼 목록이 실제와 다름 — 특히 `kind` 컬럼은 존재하지 않음 (중간)

- **ADR 서술** (`:202-205`): `task(id, pipeline_id, seq, kind, operation, status, fail_count,
  error_code, started_at, ready_at, finished_at, next_check_at, polling_interval,
  execution_timeout, max_fail_count)`.
- **구현** (`entity/Task.java`):
  - `kind` 컬럼이 없다. executor 선택은 `task_name`(mechanism 문자열; `TaskTypeRegistry`가
    `TaskType` 구현으로 해석)이 담당한다. TERRAFORM_JOB/CONDITION_CHECK는 enum이 아니라
    mechanism 이름이다 (A-4 참조).
  - `seq`가 아니라 `sequence`이고, `(pipeline_id, sequence)` UNIQUE 제약(`uq_task_pipeline_sequence`)이 있다.
  - ADR에 없는 컬럼: `task_definition`(행의 진실원 — TaskDefinition 상수 이름, 버전 불변),
    `consumes_terraform_slot`(slot 게이트용 파생 캐시, ADR-021 Decision 7),
    `description`(custom recipe 운영자 설명, LIN-18, ≤100자), `version`(`@Version` 낙관적 락 —
    pipeline 행 FOR UPDATE 직렬화에 대한 defense-in-depth).
- **권장 수정**: task 스키마 행을 실제 컬럼으로 갱신하고, "task의 `kind`가 executor를 선택한다"(§2 `:91-92`)를
  "task의 `task_name`(mechanism)이 registry를 통해 executor(TaskType)를 선택하고, `task_definition`이
  행의 진실원, `operation`이 도메인 액션"으로 수정.

### A-4. Enum 카탈로그 불일치 (중간)

- **ADR 서술** (`:89-90` "Five core enums", `:245-254` 표):
  `TaskKind` enum(TERRAFORM_JOB, CONDITION_CHECK), `PipelineType`(INSTALL, DELETE),
  `ErrorCode` 5개 값.
- **구현**:
  - `TaskKind`라는 enum은 없다. TERRAFORM_JOB/CONDITION_CHECK는 `TaskOperation`의 mechanism 축
    (String, 열린 집합 — `TaskOperation.java:5-21`)이며, 부팅 시 `TaskTypeRegistry`가 검증한다.
    ADR 자신이 §2에서 예고한 "an open set is registry-validated" 경로가 채택된 셈이다.
  - `PipelineType`에 `CUSTOM`이 추가됐다 (LIN-18, `PipelineType.java:11-17`).
  - `ErrorCode`에 `UNKNOWN_TASK`가 추가됐다 (6개 값, `ErrorCode.java:18-31`).
  - 카탈로그성 enum이 더 있다: `TaskDefinition`(task 정체성 카탈로그, 상수 이름이 String으로 영속),
    `RecipeDefinition`, `CheckSignal`, `CloudProvider`.
- **권장 수정**: Enums 표에서 `TaskKind` 행을 "mechanism (open set, String)"으로 바꾸거나 표에서 빼고
  본문으로 설명. `PipelineType`에 CUSTOM, `ErrorCode`에 UNKNOWN_TASK 추가.
  "Five core enums" 문장을 실제 구성으로 갱신.

### A-5. `task_check` 컬럼 불일치 (중간)

- **ADR 서술** (`:216-218`): `task_check(id, task_attempt_id, call_count, not_met_count,
  api_error_count, call_timeout_count, last_external_status, last_response_code,
  last_response_summary, last_checked_at)`.
- **구현** (`entity/TaskCheck.java`): `last_response_code`, `last_response_summary` 컬럼이 **없다**.
  원시 payload는 `task_attempt.response`에만 남고, `last_external_status`는 마지막 폴의 자유 형식
  디버그 레이블이다.
- **권장 수정**: 두 컬럼을 스키마 목록에서 제거. (참고: `attempt_no`도 실제 컬럼명은
  `attempt_number` — 함께 맞추면 좋다.)

### A-6. 관찰 테이블은 둘이 아니라 셋 — `terraform_result` 미반영 (중간)

- **ADR 서술** (`:96`, §3): "Two **observation tables** — `task_attempt` … and `task_check`".
- **구현**: PR #21이 postCheck 관찰 테이블 `terraform_result`를 추가했다
  (`entity/TerraformResult.java` — job당 terraform log 본문/포인터, `(task_id, attempt_number,
  job_id)` UNIQUE로 재실행 멱등, 쓰기 전용·엔진은 읽지 않음). 설계 근거는
  `docs/terraform-client-and-postcheck-design.md` §4.4다. §3의 관찰 불변식(행 유실은 진단 손실일 뿐
  정합성 손실이 아님)을 그대로 따른다.
- **권장 수정**: §3와 Schema에 세 번째 관찰 테이블로 `terraform_result`를 추가하고(1-2문장 + 설계 문서
  링크), "Two observation tables"를 "Three"로 갱신. 관계 서술(`pipeline 1:N task 1:N task_attempt
  1:0..1 task_check`)에도 `task 1:N terraform_result`(attempt·job 축) 추가.

### A-7. recipe 서술에 CUSTOM 경로 미반영 (낮음)

- **ADR 서술** (`:92`): "A pipeline's recipe (its ordered task list) is a code default per
  `(type, provider)`."
- **구현**: 카탈로그 recipe(INSTALL/DELETE × provider, `RecipeCatalog`)에 더해, 운영자가 요청에서
  task 순서를 직접 구성하는 **custom 실행**(LIN-18, `PipelineType.CUSTOM`, 비영속 recipe)이 있다
  (`PipelineCreator.createCustom`).
- **권장 수정**: 한 문장 추가 — "CUSTOM 실행은 카탈로그를 거치지 않고 요청의 task 목록을
  TaskDefinition 카탈로그로 검증해 그대로 체인으로 삽입한다."

---

## B. ADR-021 수정 필요 항목

### B-1. claim-predicate 인덱스 — PR #27이 도입한 회귀 (높음)

- **ADR 서술** (`docs/adr/021-…md:330-334`, PR #27에서 변경됨):
  > A **partial btree index on `(next_due_at) WHERE status IN ('RUNNING','PENDING')`** covers the hot path.
- **구현**: MySQL 8은 partial index를 지원하지 않는다. 실제 인덱스는
  `idx_pipeline_claim (status, next_due_at)` 복합 btree다 (`entity/Pipeline.java:38`).
  **PR #27 이전 문구**("A btree index on `(status, next_due_at)` covers the hot path — the leading
  `status` column serves the `IN` filter")**가 구현과 정확히 일치했다.** pii-agent-demo에서 문구를
  이식하면서 (PostgreSQL 전제의) partial index 서술로 덮어쓴 것이 원인이다.
  `:343-344`의 "claim-predicate **partial** index" 참조도 같이 남아 있다.
- **권장 수정**: 두 곳 모두 PR #27 이전 문구로 되돌린다. Revision history(`:515-520`)의
  "Claim predicate **and its index** widen"도 인덱스 형태 언급을 복합 인덱스 기준으로 수정.
- **부수**: admission soft-cap 카운트(`countByClaimedUntilAfter`)를 받치는
  `idx_pipeline_claimed_until` 인덱스도 존재한다 — 언급하려면 여기(claim 비용 절)가 자연스럽다(선택).

### B-2. Case A 취소 SQL — `active_target` 해제 누락 (중간)

- **ADR 서술** (`:250-252`):
  ```sql
  UPDATE pipeline SET status = 'CANCELLED', claimed_by = NULL, claimed_until = NULL …
  ```
- **구현** (`PipelineRepository.cancelIfIdle`, `:62-68`): `SET status = CANCELLED,
  active_target = NULL, claimed_by = NULL, claimed_until = NULL, last_activity_at = :now`.
  `active_target = NULL`은 장식이 아니라 **per-target uniqueness 슬롯의 해제**로, 취소 직후 같은
  target의 재실행 생성을 가능하게 하는 필수 동작이다(A-1과 동일 메커니즘). 가드는 ADR과 동일.
- **권장 수정**: A1 스니펫에 `active_target = NULL`(주석: uniqueness 슬롯 해제, ADR-016 §4)과
  `last_activity_at` 갱신을 반영.

### B-3. tx2 종료 수렴도 `active_target`을 해제한다 (중간)

- **ADR 서술** (Decision 4 `:153-158`): tx2의 고정 순서에 pipeline `status` 쓰기까지만 있음.
- **구현** (`StepReporter.terminalize`, `:128-132`): DONE/FAILED/CANCELLED 수렴 시
  `status`와 함께 `active_target = NULL`, `last_activity_at`을 같은 tx2에서 쓴다.
- **권장 수정**: Decision 4의 (4) 단계 서술에 "종단 전이면 `active_target`을 함께 비워 per-target
  uniqueness 슬롯을 해제한다(ADR-016 §4)" 한 줄 추가. ADR-016 §2의 "stored projection" 단락에도
  같은 사실을 걸어두면 두 문서가 맞물린다.

### B-4. DB 엔진/스키마 관리 전제 명시 (낮음)

- **ADR 서술**: SQL 스니펫이 PostgreSQL 문법(`now() + (:lease_seconds * interval '1 second')`,
  partial index)이고 엔진 언급이 없다.
- **구현**: 타깃은 **MySQL 8**(테스트는 H2), 접근은 JPA/Hibernate다. claim은 JPQL
  `PESSIMISTIC_WRITE` + lock-timeout `-2` 힌트로, MySQL에서 `FOR UPDATE SKIP LOCKED`로 렌더링되고
  H2에서는 일반 `FOR UPDATE`가 된다 (`PipelineRepository.java:27-29, 50-56`). 스키마는 엔티티
  어노테이션에서 `ddl-auto: update`로 생성하며 수기 마이그레이션이 없다 (`application.yml:1-4, 24`).
- **권장 수정**: Decision 2 인근에 note 한 단락 — "스니펫은 의미 설명용 의사 SQL이며, 실제 타깃은
  MySQL 8 + JPA(H2는 SKIP LOCKED 미지원으로 FOR UPDATE 폴백)". B-1 수정과 세트.

### B-5. Knob 카탈로그 이름·구성 불일치 (낮음)

- **ADR 서술** (`:450-463`): `slotCap`, `slotRetry`, `activePodCount`, `maxReplicas` 등.
- **구현** (`ExecutionSettings.java:19-31`, `application.yml:39-51`, prefix `pipeline.execution.*`):
  - 이름 다름: `slotCap` → `terraform-slot-cap`, `slotRetry` → `terraform-slot-retry`.
  - ADR에 없는 knob: `poll-interval`(일감 있을 때의 sweep 간격), `scheduler-initial-delay`.
  - `activePodCount`/`maxReplicas`는 앱 설정이 아니라 배포(k8s) 속성 — 앱 knob 표에서 구분 필요.
  - Decision 5 제약(`leaseDuration > apiCallTimeout`)은 구현이 **부팅 시 fail-fast로 강제**한다
    (`ExecutionSettings.java:49-53`) — ADR은 "운영 튜닝"이라고만 말하므로 한 줄 언급할 가치가 있다.
- **권장 수정**: Knobs 표를 실제 키 이름으로 갱신하고 위 두 knob 추가, pod 관련 두 값은
  "deployment-owned"로 표기.

---

## C. 대조 결과 일치 — 변경 불필요 (기록용)

PR #27이 이식한 PENDING 서술의 핵심은 전부 구현과 일치함을 확인했다:

- **생성 분기**: `startDelay > 0` → `PENDING` + `next_due_at = now + delay`, `== 0` → `RUNNING` fast
  path (`PipelineInserter.java:54-66`). 지연은 API 필드가 아니라 서버 설정 `pipeline.start-delay`
  (기본 PT15S, `PipelineSettings`/`application.yml:33`) — ADR-016 §2 서술 그대로.
- **PENDING → RUNNING at claim(tx1)**: lease 스탬프와 같은 UPDATE에 `status = RUNNING`을 실어 원자
  전이, 이미 RUNNING이면 no-op (`PipelineClaimer.java:43-56`). claim 술어
  `status IN (RUNNING, PENDING) AND next_due_at <= :now AND (claimed_until IS NULL OR < :now)`
  + `ORDER BY next_due_at` + SKIP LOCKED, per-claim UUID fencing token — Decision 2와 일치.
- **취소 Case A/B**: Case A 가드 `IN (RUNNING, PENDING)` + claim null/만료, Case B 가드
  `RUNNING`만(live-lease는 PENDING일 수 없음) — `PipelineRepository.cancelIfIdle`/`requestCancel`,
  `PipelineControl` 분기 로직 모두 Decision 6과 일치 (B-2의 컬럼 추가분 제외).
- **tx2 가드 라이트백**: FOR UPDATE → token-only 소유권 검증 → cancel_requested 읽기 → 전이 →
  claim 해제 + next_due_at 전진의 고정 순서 (`StepReporter`) — Decision 4와 일치.
- **soft caps**: admission은 활성 claim 수 count-read(`countByClaimedUntilAfter` ≥ cap이면 claim 안 함),
  slot 게이트는 dispatch 전 검사 → 부족하면 task READY 유지 + `next_due_at = now + slotRetry` +
  claim 해제 (`PipelineWorker`, `StepReporter.reschedule`) — Decision 7과 일치. QUEUED류 상태 없음.
- **409 계약**: `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE` + 409 Conflict
  (`PipelineAlreadyActiveException`, `OrchestrationErrorCode`) — ADR-016 §4와 일치.
- **메트릭 서술**: "RUNNING count excludes PENDING / PENDING count 별도" — Admin 통계 DTO
  (`LivePipelineStatistics`, `PipelineStatistics`)와 일치.
- **§6 재시도/폴 예산**: 폴=시도, not-met은 fail_count 예산 공유, 마지막 폴 원인이 종단 error_code,
  첫 폴 즉시(`nextCheckAt = now`) — `TaskStateMachine`/`ObservationRecorder`와 일치.

## 후속 제안

- A-1/B-2/B-3(active_target)과 B-1(인덱스)은 한 PR로 묶어 두 ADR을 동시에 고치는 편이 좋다 —
  같은 사실(MySQL 물리 설계)의 양면이다.
- A-2~A-7은 ADR-016 Schema/Enums 절 갱신 한 PR로 처리 가능.
- B-4/B-5는 선택적이지만, B-1을 고치는 김에 엔진 note를 함께 넣으면 같은 회귀의 재발을 막는다.
