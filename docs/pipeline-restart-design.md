# 파이프라인 재시작(Restart) — API·시스템 설계

> 상태: 제안 — 2026-07-25
> 관련: [ADR-016](adr/016-install-delete-pipeline-domain-model.md) §4/§5/§7,
> [ADR-021](adr/021-pipeline-execution-model.md) Decision 6, [ADR-022](adr/022-terminal-state-notification.md)

## 1. 배경과 목표

target의 최신 설치 파이프라인이 `FAILED`(실패) 또는 `CANCELLED`(타임아웃 후 취소 포함)로 끝났을 때,
운영자가 **마지막으로 실패한 task부터** 실행을 다시 이어가고 싶다.

ADR-016 §7("A terminal state is never resurrected")과 ADR-021의 race-free 증명(종료 상태는 흡수 상태)
때문에 기존 파이프라인을 되살리는 in-place resume은 불가능하다. 따라서 **재시작 = 실패 지점부터의
task suffix로 만든 새 파이프라인**이어야 한다.

이때 남는 문제가 **사용성**이다. 기존 `/custom` 엔드포인트로 suffix를 던지면 동작은 하지만:

- 새 실행이 `type = CUSTOM`으로 분류되어, 이력·통계·알림 어디에서도 "설치의 재시작"임이 드러나지 않는다.
- 어느 파이프라인을 재시작한 것인지(계보), 원본 몇 단계 중 어디부터인지, 앞 단계들이 왜 생략됐는지
  운영자가 알 수 없다.
- suffix 계산(어떤 task를 다시 돌릴지)이 클라이언트(어드민 UI) 책임이 되어 실수 여지가 생긴다.

이 설계의 목표: **재시작을 CUSTOM으로 위장하지 않고 1급(first-class) 실행으로 모델링**하되,
도메인·실행 모델(ADR-016/021)의 변경은 최소(표시용 write-once 컬럼 2개)로 억제한다.

## 2. 핵심 설계 결정

### 결정 1 — 재시작 파이프라인은 원본의 type·recipe를 승계한다

재시작된 설치 실행은 논리적으로 **여전히 설치**다(그 실행이 끝나야 설치가 완료된다). 따라서:

| 컬럼 | 재시작 파이프라인의 값 | 근거 |
|---|---|---|
| `type` | 원본과 동일 (`INSTALL`/`DELETE`/`CUSTOM`) | `type`은 write-once 표시 캐시이며 엔진 분기에 쓰이지 않는다(엔티티 주석). 이력·통계·알림이 "INSTALL"로 올바르게 분류된다. |
| `recipe_definition` | 원본과 동일 (원본이 custom이면 null) | "이 실행이 어느 recipe에 속하는가"라는 조인 링크의 의미는 유지된다. 단, **체인이 recipe의 suffix일 수 있다**는 점이 아래 provenance 컬럼으로 구분된다. |
| `cloud_provider` | 원본의 저장값 재사용 | create처럼 InfraManager를 다시 조회하지 않는다 — task들이 원본 provider 기준으로 이미 검증된 상태고, 외부 의존(503)도 제거된다. 원본 값이 열화(null)면 create와 동일하게 외부 조회로 폴백한다. |

> **왜 CUSTOM이 아닌가.** `/custom` 재사용(스키마 무변경)이 가장 싸지만, 설치 성공률 통계에서
> 재시작 성공이 INSTALL로 안 잡히고, ADR-022 알림이 "CUSTOM 종료"로 나가며, 이력 화면에서
> 재시작임을 알 수 없다 — 이번 요구(사용성)와 정면 충돌한다. CUSTOM은 "운영자가 임의 구성한 실행"
> 이라는 분류로 남기고, 재시작은 별도 축으로 모델링한다.

### 결정 2 — 계보(provenance)는 write-once 컬럼 2개로 기록한다

```
pipeline.origin_pipeline_id  BIGINT NULL   -- 이 실행이 재시작한 원본 파이프라인 id (재시작이 아니면 NULL)
task.origin_task_id          BIGINT NULL   -- 이 task가 다시 실행하는 원본 task 행 id
```

- 둘 다 **write-once 표시용 메타데이터**다. ADR-016의 관측 테이블처럼 **엔진(claim·전이·reconciler)은
  절대 읽지 않는다** — ADR-021은 한 줄도 바뀌지 않는다.
- `origin_pipeline_id`는 직전 원본을 가리킨다. 재시작의 재시작이면 체인이 되고, API에서 걸어 올라갈 수 있다.
- `task.origin_task_id`가 사용성의 핵심이다: 재시작 파이프라인의 task 상세에서 **원본 task의
  attempt·terraform 로그(실패 진단)로 바로 이동**할 수 있다.
- 역링크(원본 → 재시작)는 컬럼 없이 조회로 푼다: `origin_pipeline_id = :id`인 최신 행.
  인덱스 `idx_pipeline_origin (origin_pipeline_id)` 하나 추가.
- ADR-016에는 소규모 개정 1건이 필요하다: 스키마 절에 두 컬럼 추가 + "catalog type 파이프라인의
  task 체인은 `origin_pipeline_id`가 채워진 경우 recipe의 suffix일 수 있다" 명시.

### 결정 3 — 재시작 지점(suffix) 계산은 서버가 한다

**기본 규칙: 원본 체인에서 첫 번째 non-`DONE` task부터 끝까지.** 이 한 규칙이 두 시나리오를 모두 덮는다.

- 원본 `FAILED`: 실패 task는 `FAILED`, 후속은 `CANCELLED`(ADR-016 §7) → 첫 non-DONE = 실패한 task.
- 원본 `CANCELLED`: 비종료 task 전부 `CANCELLED` → 첫 non-DONE = 취소 당시 진행 중이던 task.

**운영자 오버라이드(`from_sequence`)는 "더 앞으로"만 허용한다.** DONE task를 다시 돌리는 것은
Terraform 수렴성(ADR-016 §5)으로 안전하다(최적화를 포기할 뿐). 반대로 실패 task를 **건너뛰는**
오버라이드는 설치 완전성을 깨므로 거절한다(400) — 그런 실행이 정말 필요하면 기존 `/custom`이 출구다.

### 결정 4 — 생성 경로·동시성 제어는 기존 것을 그대로 탄다

suffix로 만든 `PipelinePlan`을 기존 `PipelineInserter.insert()`에 넘긴다. 그러면 공짜로:

- **target당 활성 하나(409)**: `uq_pipeline_active_target` 유일 제약이 최종 심판이다. 사전 상태
  검사(원본이 terminal인가, 최신인가)는 best-effort 읽기일 뿐이고, 동시 재시작·동시 create가
  경합해도 insert에서 정확히 하나만 이긴다 — 재시작 경로에 새 race 처리가 필요 없다.
- **start-delay 시딩(PENDING)**: 재시작에도 그대로 적용된다. 부수 효과로, 취소 직후 재시작 시
  아직 InfraManager에서 돌고 있을 수 있는 이전 job과의 겹침(ADR-021 Decision 6의 accepted latency
  edge)에 자연스러운 완충이 된다(겹쳐도 멱등이라 무해하지만 큐 낭비를 줄인다).
- **fresh fail_count·fresh attempt 관측 행**: "retry is a fresh run"(§6/§7)의 파이프라인 수준 유사체.

## 3. API 설계

### 3.1 재시작 미리보기 — `GET /api/v1/target-sources/{targetSourceId}/pipelines/{pipelineId}/restart-preview`

기존 recipe preview(P9)와 같은 UX 패턴: **실행 버튼을 누르기 전에 무엇이 어떻게 돌아갈지 보여준다.**
읽기 전용, 아무것도 저장하지 않는다. 실행과 동일한 검증을 수행하므로 미리보기가 성공하면
실행도 (경합이 없는 한) 성공한다.

```json
{
  "origin": {
    "pipeline_id": 123,
    "type": "INSTALL",
    "recipe_definition": "AWS_INSTALL_V1",
    "status": "FAILED",
    "total_task_count": 8,
    "done_task_count": 5
  },
  "resume_from_sequence": 5,
  "skipped_tasks": [
    { "sequence": 0, "task_definition": "AWS_NETWORK_APPLY", "status": "DONE" }
  ],
  "tasks_to_run": [
    {
      "sequence": 5,
      "task_definition": "AWS_COMPUTE_APPLY",
      "kind": "TERRAFORM_JOB",
      "terraform_action": "APPLY",
      "origin_task_id": 1042,
      "origin_status": "FAILED",
      "origin_error_code": "EXECUTION_TIMEOUT",
      "origin_fail_count": 3
    },
    {
      "sequence": 6,
      "task_definition": "AWS_NETWORK_READY",
      "kind": "CONDITION_CHECK",
      "origin_task_id": 1043,
      "origin_status": "CANCELLED"
    }
  ],
  "warnings": [
    "원본 실행이 3분 전 취소되었습니다. 이전에 dispatch된 Terraform job이 아직 실행 중일 수 있습니다(멱등이므로 무해)."
  ]
}
```

- `origin_status`/`origin_error_code`/`origin_fail_count`로 **"왜 여기부터인지"**가 화면에서 설명된다.
- `warnings`는 차단이 아닌 안내다(현재 1종: 최근 취소/실패 직후의 in-flight job 안내.
  `last_activity_at`이 executionTimeout 창 이내면 노출).
- 쿼리 파라미터 `?from_sequence=n`으로 오버라이드 시의 미리보기도 지원한다(실행과 동일한 검증).

### 3.2 재시작 실행 — `POST /api/v1/target-sources/{targetSourceId}/pipelines/{pipelineId}/restart`

```json
// 요청 (본문 생략 가능 — 기본값: 첫 non-DONE task부터)
{ "from_sequence": 3 }

// 응답: 기존 PipelineDetail + origin 확장 (3.3)
```

검증 순서(트랜잭션 밖 → 삽입만 트랜잭션):

1. `pipelineId`가 해당 target 소속인지 — 아니면 404.
2. 원본이 terminal `FAILED`/`CANCELLED`인지 — `DONE`(재시작할 게 없음)·비종료(아직 실행 중)는 409.
3. 원본이 그 target의 **최신 실행**인지 — 과거 이력의 stale 재시작 방지, 아니면 409.
   (best-effort 검사다. 최종 동시성 심판은 4의 유일 제약.)
4. suffix의 각 `task_definition`을 `TaskDefinition.find()`로 재해석 — 카탈로그에서 사라진 이름은
   조용히 열화시키지 않고 400(`UNKNOWN_TASK`)으로 거절한다.
5. `from_sequence`가 있으면 `0 <= from_sequence <= 기본 재시작 지점`인지 — 아니면 400.
6. `PipelinePlan` 구성 후 `PipelineInserter.insert()` — active-target 유일 위반은 기존과 동일하게
   409(`ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`)로 번역.

#### 오류 계약

| 상황 | 응답 | 코드 |
|---|---|---|
| 파이프라인이 없거나 target 불일치 | 404 | `PIPELINE_NOT_FOUND` |
| 원본이 비종료(RUNNING/PENDING) 또는 DONE | 409 | `PIPELINE_NOT_RESTARTABLE` |
| 원본이 target의 최신 실행이 아님 | 409 | `PIPELINE_NOT_LATEST` |
| suffix에 해석 불가 task 이름 | 400 | `UNKNOWN_TASK` |
| `from_sequence` 범위 밖(음수·기본 지점보다 뒤·체인 밖) | 400 | `INVALID_RESUME_SEQUENCE` |
| 삽입 시 활성 실행 존재(경합 포함) | 409 | `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE` |

### 3.3 조회 표면 확장 — 재시작이 이력·상세에서 "보이게"

**`PipelineSummary`(P3/P7/P8)** 에 1필드 추가:

```json
{ "origin_pipeline_id": 123 }
```

이것만으로 이력 목록에서 "재시작" 배지와 원본 링크를 그릴 수 있다. 목록 행마다 조인이 필요 없다
(자기 행의 컬럼이다).

**`PipelineDetail`(P4/P10, restart 응답 공용)** 에 origin 블록 추가:

```json
{
  "origin_pipeline_id": 123,
  "origin": {
    "pipeline_id": 123,
    "type": "INSTALL",
    "recipe_definition": "AWS_INSTALL_V1",
    "status": "FAILED",
    "total_task_count": 8,
    "done_task_count": 5,
    "resumed_from_sequence": 5
  },
  "restarted_by_pipeline_id": null
}
```

- `origin`은 `origin_pipeline_id != null`일 때만 채워진다(1회 단건 조회).
- `restarted_by_pipeline_id`는 **역링크**다: 실패한 원본 파이프라인 상세를 보는 운영자가
  "이 실행은 #124로 재시작됨"을 즉시 안다(`origin_pipeline_id = :id`인 최신 행 조회,
  `idx_pipeline_origin`이 지원). 화면에서 실패 → 재시작 → 재재시작 체인을 양방향으로 오갈 수 있다.
- 진행률 표기: 재시작 파이프라인의 `done/total`은 자기 suffix 기준(예: 0/3)을 유지한다 —
  숫자를 조작하지 않고, UI가 origin 블록으로 "원본 8단계 중 6단계부터"를 함께 렌더링한다.

**`TaskSummary`/`TaskDetail`** 에 `origin_task_id` 추가. UI는 이 링크로 원본 task의
attempt 이력·`terraform_result` 로그(실패 진단의 원천)로 바로 이동한다.

### 3.4 ADR-022 알림

`NotifyPayload`에 `origin_pipeline_id`(과 원본 type)를 실어, 재시작 실행의 종단 알림이
"INSTALL(#123의 재시작) 성공/실패"로 나가게 한다. 알림 엔진 로직은 무변경 — payload 필드 추가만.

## 4. 시스템 설계

### 4.1 컴포넌트

```
TargetSourcePipelineController
  ├─ GET  …/{pipelineId}/restart-preview  ──▶ PipelineRestarter.preview()
  └─ POST …/{pipelineId}/restart          ──▶ PipelineRestarter.restart()

PipelineRestarter (신규, service/lifecycle)
  1) 원본 pipeline + task 체인 로드, 검증(404/409/400 — §3.2)
  2) suffix 계산(첫 non-DONE, 오버라이드 반영)
  3) TaskDefinition.find()로 재해석 → PlannedStep(definition, description, originTaskId)
  4) PipelinePlan.restartOf(origin, steps) 구성
  5) PipelineInserter.insert() 호출, 유일 위반 → 409 번역 (PipelineCreator.insert()와 동일 패턴)

PipelinePlan (확장)
  + originPipelineId (nullable)                      — restart 경로만 채움
  + PlannedStep.originTaskId (nullable)
  + static restartOf(...): type/provider/recipeDefinition을 원본에서 승계

PipelineInserter (확장)
  + pipeline.originPipelineId, task.originTaskId 스탬핑 — 그 외 로직 무변경
    (첫 task READY·나머지 BLOCKED, sequence 0부터 재부여, startDelay 시딩 모두 기존 그대로)
```

- `PipelineRestarter`는 `PipelineCreator`와 마찬가지로 **클래스 수준 `@Transactional` 없음** —
  읽기·검증은 트랜잭션 밖, 삽입만 inserter의 트랜잭션(docs/exception-strategy.md 패턴).
- **sequence는 0부터 재부여**한다(원본 번호 보존 안 함). `(pipeline_id, sequence)` 유일 제약·
  "current task = 최저 sequence" 규칙 등 기존 코드가 건드려지지 않고, 원본과의 대응은
  `origin_task_id`가 진다. 화면의 "원본 6/8단계" 표기는 origin 블록에서 계산한다.
- `description` 승계: 원본이 CUSTOM이었으면 운영자 설명을 그대로 복사한다(재시작에서도 같은 문맥).

### 4.2 엔티티·스키마 변경 요약

| 대상 | 변경 | 성격 |
|---|---|---|
| `pipeline` | `origin_pipeline_id BIGINT NULL` (write-once, `updatable=false`) + `idx_pipeline_origin` | 표시용 메타데이터. 엔진 미접근. |
| `task` | `origin_task_id BIGINT NULL` (write-once, `updatable=false`) | 표시용 메타데이터. 엔진 미접근. |
| FK 제약 | **걸지 않는다** | 원본 행 보존은 운영 정책의 몫이고, 열화(원본 삭제) 시에도 조회가 null-safe로 동작하면 충분하다 — 카탈로그 이름 열화와 같은 태도. |

### 4.3 ADR 정합성 체크

| 원칙 | 이 설계에서 |
|---|---|
| ADR-016 §7 terminal 불부활 | 원본은 손대지 않는다. 재시작은 항상 새 행. |
| ADR-016 §4 target당 활성 하나 | 기존 `active_target` 유일 제약이 그대로 심판. 사전 검사는 UX용 best-effort. |
| ADR-016 §5 멱등성 | suffix 경계가 보수적이어도(DONE 재실행 오버라이드 포함) 정확성 무해 — Terraform 수렴. |
| ADR-016 §2 task_definition = 진실원인 | 재시작 plan은 원본 task 행의 `task_definition` 문자열을 재해석해 만든다. 해석 실패는 400(열화 금지). |
| ADR-021 전체 | **무변경.** 엔진에게 재시작 파이프라인은 그냥 새 파이프라인이다. 신규 컬럼 2개는 claim·write-back·cancel 어느 경로도 읽지 않는다. |
| ADR-022 | payload 필드 추가만. 알림 상태 기계 무변경. |
| 필요한 ADR 개정 | ADR-016 스키마 절에 provenance 컬럼 2개 추가 + "catalog type 체인은 재시작 시 recipe suffix일 수 있음" 1문장. |

## 5. 사용성 관점 요약 (무엇이 좋아지는가)

1. **실행 전 미리보기**: 어떤 task가 왜(원본 상태·에러코드) 다시 돌아가는지, 무엇이 생략되는지
   보고 누른다. 기존 P9 preview와 동일한 조작 문법.
2. **이력에서 식별**: 목록의 `origin_pipeline_id`로 "재시작" 배지 + 원본 링크. type은 INSTALL로
   유지되므로 통계·필터도 오염되지 않는다.
3. **양방향 계보 탐색**: 실패 파이프라인 → `restarted_by_pipeline_id` → 재시작 실행,
   재시작 실행 → `origin` → 실패 원본. 재시작의 재시작도 체인으로 걸어 올라간다.
4. **task 단위 진단 연결**: 재시작 task의 `origin_task_id`로 원본의 attempt·terraform 로그에
   바로 진입 — "지난번엔 왜 죽었는지"를 새 실행 화면에서 벗어나지 않고 확인.
5. **실수 방지 가드**: 최신이 아닌 실행의 재시작 거절(409), 실패 task 건너뛰기 거절(400),
   사라진 task 정의는 조용한 열화 대신 명시적 400.
6. **알림 문맥**: 종단 알림이 "CUSTOM"이 아니라 "INSTALL(재시작)"로 도착.

## 6. 엣지 케이스

- **원본의 모든 task가 non-DONE**(PENDING 상태에서 취소 등): suffix = 전체 체인 = 사실상 전체 재실행.
  규칙이 그대로 덮으므로 특수 처리 없음. 미리보기에 `skipped_tasks: []`로 드러난다.
- **원본이 CUSTOM**: type=CUSTOM 승계, `recipe_definition=null`, description 복사. 나머지 동일.
- **취소 직후 재시작**: 이전 dispatch job이 InfraManager에서 아직 실행 중일 수 있다(ADR-021
  Decision 6 accepted edge). 멱등이라 무해하고, start-delay(PENDING) 시딩이 완충하며,
  미리보기 `warnings`로 안내한다. 차단하지 않는다.
- **동시 재시작 2건 / 재시작 vs create 경합**: 유일 제약에서 하나만 성공, 나머지 409 — 신규 코드 없음.
- **재시작 실행이 또 실패**: 그 실행을 다시 재시작하면 된다. `origin_pipeline_id`는 직전 실행을
  가리키고, "최신 실행만 재시작 가능" 가드가 항상 체인의 끝에서만 재시작하게 강제한다.
- **원본 provider 열화(null)**: create 경로와 동일하게 InfraManager 조회로 폴백, 실패 시 503.

## 7. 구현 순서 제안

1. **스키마·plan 확장**: `origin_pipeline_id`/`origin_task_id` 컬럼, `PipelinePlan.restartOf`,
   `PipelineInserter` 스탬핑. (엔진 무접촉 — 회귀 위험 최소)
2. **`PipelineRestarter` + POST /restart**: suffix 계산·검증·오류 계약 + 통합 테스트
   (FAILED 재시작 / CANCELLED 재시작 / 최신 아님 / DONE / 활성 존재 / UNKNOWN_TASK / from_sequence 경계).
3. **GET /restart-preview**: restarter의 검증·계산 로직 재사용(읽기 전용 분리).
4. **조회 확장**: Summary/Detail/TaskSummary 필드, 역링크 조회, NotifyPayload 필드.
5. **ADR-016 개정 1건** + acceptance-criteria 항목 추가.
