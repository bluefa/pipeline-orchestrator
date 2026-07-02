# Admin Pipeline Dashboard — 페이지별 기능 · API 매핑

ADR-016(도메인 모델) · ADR-021(실행 모델) 기준. 각 페이지의 **조회/액션/이동**을 나열하고,
그 정보를 제공하는 **API**를 매핑한다.

> 이 문서는 **요구사항 원본**이다. 이 저장소(pipeline-orchestrator = ADR-016 도메인 모델) 기준의
> 실제 구현 가능 여부·정정된 스키마는 [pipeline-api-feasibility.md](pipeline-api-feasibility.md) 참조.

## 범례 · 전제

- ✅ **존재** — `docs/swagger/install-v1.yaml` 에 이미 정의됨(operationId 표기). 계약 정본이며
  `scripts/gen-api.mjs` → `lib/generated/install-v1.ts` 로 zod 스키마 생성(loose). route는 `.parse()`,
  client는 `z.infer`. 새 필드/엔드포인트는 **반드시 swagger 먼저** 수정 후 `npm run gen:api`.
- 🆕 **신규 필요** — pipeline 관련 API는 **현재 install-v1.yaml에 태그/경로가 전혀 없음**(확인 완료).
  아래 제안 경로는 초안이며, 확정 시 swagger에 먼저 반영해야 한다.
- 도메인 필드는 ADR-016 §Schema, 실행 필드는 ADR-021 Decision 2를 그대로 인용한다.

> **선확인 결론**: 사용자가 "빠졌을 것"이라 한 **서비스 상세 / target source 상세 / installation status**는
> 대부분 **이미 존재**한다(아래 ✅). 실제로 없는 것은 **pipeline/task 계열 전부**다.

### 도메인 데이터 원천 (ADR-016 / ADR-021)

| 테이블 | 컬럼(발췌) |
|---|---|
| `pipeline` | id, type(INSTALL/DELETE), target(=target_source_id), status(RUNNING/DONE/FAILED/CANCELLED), created_at, last_activity_at, **next_due_at, claimed_by, claimed_until, cancel_requested** |
| `task` | id, pipeline_id, seq, kind(TERRAFORM_JOB/CONDITION_CHECK), operation, status(BLOCKED/READY/IN_PROGRESS/DONE/FAILED/CANCELLED), fail_count, max_fail_count, error_code, started_at, ready_at, finished_at, next_check_at, ttl, polling_interval, execution_timeout |
| `task_attempt` | id, task_id, attempt_no, job_ids[], status, error_code, dispatch_response_code, dispatch_response_summary, started_at, finished_at |
| `task_check` | id, task_attempt_id, call_count, not_met_count, api_error_count, call_timeout_count, last_external_status, last_response_code, last_response_summary, last_checked_at |

---

## 1. 대시보드 — `admin/pipeline/dashboard`

### 조회 — 실시간 현황 (필터 무관, 순간값)

| 기능 | 데이터 | API |
|---|---|---|
| 동작 중 파이프라인 개수 | `count(pipeline WHERE status='RUNNING')` | 🆕 `GET /install/v1/pipelines/stats/live` |
| 전체 Worker 개수 | ADR-021 metric: `total worker count = activePodCount × workerPerPod` | 🆕 위와 동일 (⚠️ 주의 1) |
| 동작 중 Terraform task 개수 | `count(task WHERE kind='TERRAFORM_JOB' AND status='IN_PROGRESS')` | 🆕 위와 동일 (⚠️ 주의 1) |

> **⚠️ 주의 1 — worker/TF 수치는 도메인 DB에 없다.** Worker 총수는 오케스트레이터 런타임 지표(ADR-021)다.

### 조회 — 기간 통계 (기간 필터 연동)

| 기능 | 데이터 | API |
|---|---|---|
| Running 파이프라인 개수 | 기간 내 status별 집계 | 🆕 `GET /install/v1/pipelines/stats?period=1h\|1d\|7d` |
| 실패 파이프라인 개수 | 〃 (FAILED) | 〃 |
| 성공 파이프라인 개수 | 〃 (DONE) | 〃 |

> **⚠️ 주의 2 — "기간 내 Running" 의미 모호.** 집계 기준 컬럼(`created_at` vs `last_activity_at`)을 명시할 것.

### 조회 — 파이프라인 목록 (행당)

| 컬럼 | 데이터 | 원천 |
|---|---|---|
| target | `pipeline.target`(=target_source_id) + **표시명** | 🆕 목록 API + ⚠️ 주의 3 |
| Cloud Provider | target source의 `cloud_provider` | ⚠️ 주의 3 |
| 파이프라인 상태 | `pipeline.status` | 목록 API |
| 진행 현황 N/M (task 기준) | `count(task DONE) / count(task)` per pipeline | 목록 API |
| 상세보기 | link → 4번 페이지 | — |

- 🆕 `GET /install/v1/pipelines?period=&status=&provider=&page=&size=` → `Page<PipelineSummary>`

> **⚠️ 주의 3 — pipeline 테이블엔 target 이름/CSP가 없다.** `target_name`·`cloud_provider`는 target-source에서 조인.

### 액션 / 이동
- 기간 필터·목록 필터·페이지 이동 → 목록/통계 API 쿼리
- 행 상세보기 → 4. 파이프라인 상세 (`pipeline_id`)

---

## 2. 서비스·대상 검색 — `admin/pipeline/services`

| 기능 | API | 상태 |
|---|---|---|
| 서비스 코드 검색 결과 | `getUserServices` → `PageServiceItem` | ✅ |
| 선택 서비스의 target source id 목록 | `getTargetSourcesByServiceCode` → `TargetSourceDetail[]` | ✅ |
| (참고) 서비스 권한 사용자 | `getServiceAuthorizedUsers` | ✅ |

### 액션 / 이동
- 서비스 코드 검색 → `getUserServices`
- target source 선택 → 3. 대상 이력 (`target/{targetSourceId}`)

---

## 3. 대상 이력 페이지 — `admin/pipeline/target/{targetSourceId}`

### 조회 — TargetSource 메타데이터 (헤더) — ✅ 대부분 존재

`getTargetSourceDetail` → `TargetSourceDetail` (CSP, 계정 정보, 서비스 이름/코드, `process_status` — ⚠️ 주의 4)

> **⚠️ 주의 4 — "설치 상태"가 두 종류다.** 라이프사이클(`process_status`, `getProcessStatus`) vs 리소스/검증
> (CSP별 `getAwsInstallationStatus` 등). 모두 ✅. 어느 것을 "설치 상태"로 볼지 확정.

### 조회 — 최근 파이프라인 / 이력 목록

| 기능 | API |
|---|---|
| 최신 파이프라인 1건 | 🆕 `GET /install/v1/target-sources/{targetSourceId}/pipelines/latest` |
| 대상의 파이프라인 이력 + 페이지네이션 | 🆕 `GET /install/v1/target-sources/{targetSourceId}/pipelines?page=&size=` |

### 액션

| 액션 | API | 규칙 |
|---|---|---|
| 설치(INSTALL) 실행 | 🆕 `POST /install/v1/target-sources/{targetSourceId}/pipelines` `{type:"INSTALL"}` | **ADR-016 §4 유일성**: 기존 run 반환(200). |
| ↳ 실행 전 확인 | 🆕 `GET .../pipelines/preview?type=INSTALL` | recipe(순서 task 목록) |
| 삭제(DELETE) 실행 | 🆕 `POST .../pipelines` `{type:"DELETE"}` | 위와 동일 |
| ↳ 실행 전 확인 | 🆕 `GET .../pipelines/preview?type=DELETE` | — |
| 최근 파이프라인 취소 | 🆕 `POST /install/v1/pipelines/{pipelineId}/cancel` | ADR-021 §6: idle→즉시, live→cooperative |

### 이동
- 이력 행 클릭 → 4. 파이프라인 상세
- 헤더 대상명 → Target Source 관리 상세

---

## 4. 파이프라인 상세 페이지

### 조회 — Pipeline 메타데이터 — 🆕 `GET /install/v1/pipelines/{pipelineId}` → `PipelineDetail`

ID/타입/target/상태, 생성·마지막 활동 시각, 현재/최종 task, `next_due_at`, lease 점유 여부,
취소 요청 여부, 실패 횟수/한계치, 지연 lag.

### 조회 — Task 흐름 시각화 (n8n 스타일, 읽기 전용)

`PipelineDetail.tasks[]`: `seq, kind, operation, status, fail_count, error_code, started_at, finished_at` (선형 체인).

### 조회 — Task 상세 패널 — 🆕 `GET /install/v1/pipelines/{pipelineId}/tasks/{taskId}` → `TaskDetail`

Task 전체 컬럼 + `task_attempt[]`(attempt_no, job_ids, status, error_code, dispatch_response_*, started/finished) +
`task_check`(attempt별 1건 폴링 요약).

### 액션
- task 노드 클릭 → 상세 패널
- 파이프라인 취소 → `POST /install/v1/pipelines/{pipelineId}/cancel`

---

## 신규 API 요약 (pipeline 계열 — 전부 🆕)

| # | Method | Path | 용도 |
|---|---|---|---|
| P1 | GET | `/install/v1/pipelines/stats/live` | 실시간 현황(running count, worker/TF — ⚠️주의1) |
| P2 | GET | `/install/v1/pipelines/stats?period=` | 기간 통계(running/failed/success) |
| P3 | GET | `/install/v1/pipelines?period=&status=&provider=&page=&size=` | 대시보드 목록 |
| P4 | GET | `/install/v1/pipelines/{pipelineId}` | 파이프라인 상세(메타+실행메타+tasks) |
| P5 | GET | `/install/v1/pipelines/{pipelineId}/tasks/{taskId}` | Task 상세(attempt/check) |
| P6 | POST | `/install/v1/pipelines/{pipelineId}/cancel` | 취소 (idle 즉시 / live cooperative) |
| P7 | GET | `/install/v1/target-sources/{targetSourceId}/pipelines` | 대상 이력 목록 |
| P8 | GET | `/install/v1/target-sources/{targetSourceId}/pipelines/latest` | 최근 1건 카드 |
| P9 | GET | `/install/v1/target-sources/{targetSourceId}/pipelines/preview?type=` | 실행 전 recipe 미리보기 |
| P10 | POST | `/install/v1/target-sources/{targetSourceId}/pipelines` | INSTALL/DELETE 실행(유일성: 기존 run 반환) |

## 확정 필요 (open questions)

1. **worker/TF task 수치 원천** — orchestrator metrics 프록시 vs `IN_PROGRESS` task count 근사 (주의 1).
2. **"기간 내 Running" 정의** — 집계 컬럼(`created_at` vs `last_activity_at`) (주의 2).
3. **목록 target 조인** — 서버가 `target_name`/`cloud_provider`를 `PipelineSummary`에 실을지 (주의 3).
4. **"설치 상태" 정의** — `process_status`(라이프사이클) vs CSP installation-status(리소스) (주의 4).
5. **진행 N/M 계산** — CANCELLED/BLOCKED task를 분모/분자에 어떻게 셀지.

---

## 부록 — 페이지별 "화면에 실제 표시 가능한 필드" (프론트 렌더링 관점)

각 페이지가 요구하는 UI 항목을 **이 저장소(ADR-016 오케스트레이터)의 실제 데이터로 렌더할 수 있는가**로
표기한다. 근거·정정 스키마는 [pipeline-api-feasibility.md](pipeline-api-feasibility.md) 참조.

**범례**
- ✅ **표시 가능** — 이 저장소 엔티티 필드로 그대로 렌더.
- ⚙️ **표시 가능(파생/집계)** — 계산해서 렌더(단일 값 아님).
- ⚠️ **외부 조인 필요** — target-source 등 **다른 서비스** 값을 합쳐야 표시(이 저장소엔 id만 있음).
- ❌ **표시 불가(현재)** — ADR-021 실행 필드 등 이 저장소에 **데이터 자체가 없음**.
- 🔵 **기존 API** — 다른 서비스에 이미 존재(이 저장소 무관, ✅ 취급).

### 1. 대시보드

| UI 항목 | 표시 | 렌더 소스 / 비고 |
|---|---|---|
| 동작 중 파이프라인 개수 (= 사용 중 slot 수) | ✅ | `count(status=RUNNING)` |
| 총 slot 수 / 동시 수행 pipeline 리밋 (구 "Worker 개수") | ❌ | = ADR-021 러너 동시성 용량. 이 저장소 **설정·데이터 없음**. 분자(사용 slot=RUNNING)는 ✅ 제공, **분모(총 리밋)는 ADR-021**에서 옴. "3 / 50 slots"의 50만 외부 |
| 동작 중 Terraform task 개수 | ⚙️ | `count(task taskName=TERRAFORM_JOB, IN_PROGRESS)` — 근사치임을 배지로 표기 권장 |
| Running/실패/성공 개수(기간) | ✅ | status별 집계(`created_at` 기준, Q2) |
| 목록: target(id) | ✅ | `pipeline.target` |
| 목록: target **표시명** | ⚠️ | **다른 repo(target-source) 담당** — 이 저장소 범위 밖 |
| 목록: Cloud Provider | ⚠️ | **다른 repo(target-source) 담당** — 이 저장소 범위 밖 |
| 목록: 파이프라인 상태 | ✅ | `pipeline.status` |
| 목록: 진행 N/M | ⚙️ | task 집계(`DONE / 전체`, Q5) |
| 목록: 상세보기 링크 | ✅ | `pipeline_id` |

### 2. 서비스·대상 검색

| UI 항목 | 표시 | 비고 |
|---|---|---|
| 서비스 코드 검색 결과 | 🔵 | `getUserServices`(기존) |
| 서비스별 target source id 목록 | 🔵 | `getTargetSourcesByServiceCode`(기존) |
| (참고) 서비스 권한 사용자 | 🔵 | `getServiceAuthorizedUsers`(기존) |

> 전부 기존 API. **이 저장소 신규 작업 없음.**

### 3. 대상 이력

| UI 항목 | 표시 | 렌더 소스 / 비고 |
|---|---|---|
| 헤더: CSP / 계정정보 / 서비스명·코드 | 🔵 | `getTargetSourceDetail`(기존) |
| 헤더: 설치 상태(process_status) | 🔵 | `getProcessStatus` / CSP installation-status(기존, Q4) |
| 최근 파이프라인 카드(1건) + 상태 | ✅ | P8 `findFirstByTargetOrderByCreatedAtDesc` |
| 이력 목록 + 페이지네이션 | ✅ | P7 `findByTargetOrderByCreatedAtDesc` |
| 이력 행: 표시명/CSP | ⚠️ | target-source 조인(대시보드 목록과 동일) |
| 액션: INSTALL/DELETE 실행 | ✅ | P10 `PipelineCreator.create`(멱등·기존 run 반환) |
| 액션: 실행 전 preview | ✅ | P9 `Recipes.forType` |
| 액션: 취소 | ✅ | P6 `PipelineControl.cancel` — **항상 동기·즉시**(idle/cooperative 구분 없음, Q7) |

### 4. 파이프라인 상세

**Pipeline 메타데이터**

| UI 항목 | 표시 | 렌더 소스 / 비고 |
|---|---|---|
| ID / 타입 / target / 상태 | ✅ | `Pipeline` 필드(target 표시명은 ⚠️ 외부) |
| 생성 / 마지막 활동 시각 | ✅ | `createdAt` / `lastActivityAt` |
| 현재 / 최종 task | ⚙️ | 파생(최저 READY/IN_PROGRESS seq / max seq) |
| 실패 횟수 / 한계치 | ⚙️ | 현재 task `failCount` / `TaskSettings.resolveMaxFailCount` |
| **다음 예정 시각(next_due_at)** | ❌ | ADR-021. 이 저장소 없음 |
| **lease 점유 여부(claimed_until)** | ❌ | ADR-021. 이 저장소 없음 |
| **취소 요청 여부(cancel_requested)** | ❌ | ADR-021. 이 저장소 없음(취소는 즉시 CANCELLED) |
| **지연 lag(now−next_due_at)** | ❌ | ADR-021. 이 저장소 없음 |

**Task 흐름 노드 (선형 체인)**

| UI 항목 | 표시 | 비고 |
|---|---|---|
| seq / kind / operation / status | ✅ | `kind = taskName`("TERRAFORM_JOB"/"CONDITION_CHECK") |
| fail_count / error_code | ✅ | `Task.failCount` / `errorCode`(FAILED일 때만) |
| started_at / finished_at | ✅ | — |

**Task 상세 패널 (노드 클릭)**

| UI 항목 | 표시 | 렌더 소스 / 비고 |
|---|---|---|
| task 전체 컬럼(설정 포함) | ✅ | 유효 설정은 `TaskSettings.resolve*`로 계산 |
| attempt: attempt_no/status/error_code/시각 | ✅ | `findByTaskIdOrderByAttemptNumberAsc` |
| attempt: **원시 응답(response)** | ✅ | `TaskAttempt.response`(text) |
| attempt: **job_ids / dispatch_response_code·summary** | ❌ | **제거된 필드**(리팩터 ed97ec0). job id는 `response` 안에 있고 task type이 파싱 |
| 폴링 요약: call/not_met/api_error/call_timeout | ✅ | `TaskCheck` 카운터 |
| 폴링 요약: last_external_status / last_checked_at | ✅ | — |
| 폴링 요약: **last_response_code / summary** | ⚠️ | 컬럼은 있으나 **현재 미채움**(향후 HTTP 어댑터) → 대개 null |
| 액션: 취소 | ✅ | P6(위와 동일) |

### 한눈 요약 — "못 보여주는 것"만

- ❌ **총 slot 리밋**(구 "Worker 총수", 대시보드) · **next_due_at / lease / cancel_requested / lag**(상세 메타) — 전부 **ADR-021 실행 필드**, 이 저장소에 데이터 없음. (단 대시보드의 *사용 중* slot = RUNNING count 는 ✅.)
- ❌ **job_ids / dispatch_response_\***(task attempt) — 리팩터로 제거, 원시 `response` 텍스트로 대체됨.
- ⚠️ **target 표시명 / Cloud Provider** — **다른 repo(target-source) 담당**, 이 저장소 범위 밖. 여기선 `target`(id)만 제공.
- ⚠️ **last_response_code / summary** — 필드 존재하나 현재 미채움(null).
- 그 외 목록/상태/이력/task 흐름/attempt/폴링요약/실행·취소·preview 는 **모두 표시 가능**(✅/⚙️).
