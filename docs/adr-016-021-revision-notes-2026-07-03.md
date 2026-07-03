# ADR-016 / ADR-021 정합성 수정 내역 (2026-07-03 alignment pass)

[adr-016-021-implementation-gap-review.md](adr-016-021-implementation-gap-review.md)에서 확인한
불일치(A-1~A-7, B-1~B-5)를 두 ADR에 반영한 수정 내역이다. **설계 결정 자체를 바꾼 항목은 없다** —
모든 수정은 "ADR이 구현과 다르게 기술하던 사실"을 실제 구현(main@e2afc6a)에 맞춘 것이다.
아래에 두 ADR의 **모든 Decision/절**을 빠짐없이 나열하고, 각각 무엇을 왜 바꿨는지(또는 왜 바꾸지
않았는지)를 기술한다.

---

## ADR-016 (도메인 모델)

### Status

- **변경**: revision 항목 한 줄 추가 — "alignment pass: schema/enum catalog matched to the
  implementation".
- **왜**: 이번 수정이 설계 변경이 아니라 문서-구현 정렬임을 개정 이력에 명시하기 위해서다.
  LIN-30 revision과 같은 날짜라 "(alignment pass)"로 구분했다.

### Context

- **변경 없음.** 규모(~2,000 targets), InfraManager 제약, 관계 시스템 서술은 여전히 유효하다.
  "~12 pipeline shapes (provider × install/delete)"는 카탈로그 recipe의 규모 서술이므로 CUSTOM
  추가와 모순되지 않는다(§2에서 CUSTOM을 별도로 기술).

### Decision 1 — The database is the only state

- **변경 없음.** 구현이 그대로 따른다(상태는 전부 `pipeline`/`task` 행; 워커는 stateless).

### Decision 2 — Two domain tables, a small durable state machine

세 가지를 고쳤다.

1. **"Five core enums (… `TaskKind` …)" 단락 재작성** (A-4, A-3).
   - 이전: core enum 5개에 `TaskKind`(TERRAFORM_JOB, CONDITION_CHECK)가 포함되고, "task의
     `kind`가 executor를 선택한다"고 서술.
   - 이후: core enum은 4개(`TaskStatus`, `PipelineStatus`, `PipelineType`, `ErrorCode`) +
     closed `TaskOperation`. task *kind*는 **enum이 아니라 mechanism** — 열린 `TaskType`
     executor 이름 집합으로, 부팅 시 registry가 검증하고 `task_name`으로 영속된다. 행의
     진실원은 `task_definition`(버전 불변 `TaskDefinition` 상수 이름)이고 `task_name`/`operation`은
     write-once 파생 캐시다.
   - **왜**: 구현에 `TaskKind` enum이 존재하지 않는다. executor 선택은
     `Task.taskName` → `TaskTypeRegistry` → `TaskType` 경로다(`model/TaskType.java`,
     `enums/TaskOperation.java`의 mechanism 축). ADR이 §2에서 스스로 예고했던 "an open set is
     registry-validated" 분기가 실제로 채택된 것이므로, 문서를 채택된 쪽으로 확정했다.
2. **recipe 서술에 CUSTOM 한 문장 추가** (A-7).
   - "CUSTOM pipeline은 카탈로그 대신 운영자 요청의 task 목록을 TaskDefinition 카탈로그로
     검증해 사용한다(비영속 recipe)."
   - **왜**: LIN-18(`PipelineCreator.createCustom`, `PipelineType.CUSTOM`)이 이미 구현·출시된
     경로인데 ADR은 카탈로그 recipe만 기술하고 있었다.
3. **stored-projection 단락에 한 문장 추가** (B-3의 ADR-016 측).
   - "종단 전이는 같은 트랜잭션에서 `active_target` uniqueness 슬롯도 비운다(Decision 4, Schema)."
   - **왜**: status의 종단 전이와 uniqueness 슬롯 해제는 구현에서 항상 한 트랜잭션에 묶인다
     (`StepReporter.terminalize`, `cancelIfIdle`). 이 원자성이 "취소/완료 직후 같은 target 재실행
     생성 가능"을 보장하는 규칙이므로 도메인 ADR에 명문화했다.
   - 부수: "lowest-`seq`" 표기 2곳(§2, Glossary)을 실제 컬럼명 `sequence`로 통일했다.

### Decision 3 — Observation is separate from state

- **변경**: "Two observation tables" → "**Three** observation tables" — `terraform_result`
  (job별 Terraform log, attempt 판정 시점 기록)를 셋째 테이블로 추가 (A-6).
- **왜**: PR #21이 postCheck 관찰 테이블 `terraform_result`를 추가했다
  (`entity/TerraformResult.java`, 설계: terraform-client-and-postcheck-design.md §4.4).
  이 테이블은 §3의 원칙(쓰기 전용, 엔진은 읽지 않음, 행 유실=진단 손실)을 그대로 따르므로
  Decision의 논지는 변하지 않고 목록만 넓어진다. 상세 스키마는 Schema 절에 두었다.

### Decision 4 — One active pipeline per target

- **변경**: 첫 문장의 괄호에 "the `active_target` slot column is stamped at insert; see
  **Schema**"를 추가 (A-1의 본문 측).
- **왜**: "PENDING이 생성 순간부터 슬롯을 쥔다"는 기존 문장은 참이지만, 그 이유가 (기존 서술처럼)
  상태 조건부 인덱스가 아니라 **insert 시점에 채워지는 `active_target` 컬럼**이기 때문임을
  가리키게 했다(`PipelineInserter.insert`가 status와 무관하게 `activeTarget = target`을 세팅).
  409 계약, 트리거-경로 한정 등 나머지 서술은 구현(`PipelineAlreadyActiveException`,
  `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`)과 일치해 그대로 두었다.

### Decision 5 — Correctness rests on idempotency, not exactly-once

- **변경**: 표기 2건만 — "each `TaskKind` deserializing" → "each `TaskType` deserializing",
  "`(task_id, attempt_no)`" → "`(task_id, attempt_number)`".
- **왜**: Decision 2에서 `TaskKind`를 제거한 것과의 일관성, 그리고 실제 컬럼명(`attempt_number`,
  `TaskAttempt.java`)과의 일치. 멱등성/at-least-once 논지는 구현과 일치해 손대지 않았다.

### Decision 6 — Bounded waiting and retry

- **변경 없음.** 2026-07-01 revision에서 이미 구현과 정렬됐고, 이번 대조에서도
  `TaskStateMachine`(폴=시도, not-met은 fail_count 예산 공유, 마지막 폴 원인이 종단
  `error_code`, 첫 폴 즉시, 재시도 간격 `polling_interval`)과 일치함을 확인했다.

### Decision 7 — Minimal lifecycle

- **변경 없음.** "Two task kinds"는 mechanism 관점에서 여전히 참이고(TERRAFORM_JOB /
  CONDITION_CHECK), cancel의 수렴 규칙(비종료 task 전부 CANCELLED, FAILED pipeline은 실패
  task만 FAILED)은 `TaskCanceller`/`StepReporter.converge`와 일치한다.

### Considered Options / Consequences

- **Consequences만 한 단어 변경**: "the two observation tables" → "the three observation
  tables" (A-6의 파급). Considered Options는 변경 없음.

### Schema

가장 많이 고친 절이다.

1. **`pipeline` 행** (A-1, A-2): 컬럼 목록에 `cloud_provider`, `recipe_definition`,
   `active_target` 추가. "partial unique index on `target` over non-terminal `status`" 서술을
   삭제하고, **`active_target` 메커니즘**으로 교체 — MySQL 8은 partial(filtered) unique index를
   지원하지 않으므로, 애플리케이션이 비종료 동안 `active_target = target`을 유지하고 종단
   전이와 같은 트랜잭션에서 `NULL`로 비우며, 그 컬럼의 일반 UNIQUE 제약이 불변식을 짊어진다
   (MySQL은 UNIQUE에 NULL 중복 허용 → 종료 행끼리 충돌 없음). 409는 제약 **이름** 매칭으로
   번역된다는 사실도 추가.
   - **왜**: 기존 서술은 PostgreSQL에서만 가능한 물리 설계였다. 실제 구현은
     `Pipeline.java`의 `uq_pipeline_active_target` 제약 + `PipelineCreator.isActiveTargetViolation`
     (제약 이름 판별)이다. 불변식 자체("target당 비종료 1개, 경쟁 패자는 409")는 동일하고
     **집행 수단만** 문서에서 틀려 있었다.
2. **`task` 행** (A-3): `seq`→`sequence`, 존재하지 않는 `kind` 컬럼 삭제, 실제 컬럼
   `task_name`(mechanism), `task_definition`(진실원), `consumes_terraform_slot`(slot 게이트
   캐시), `description`(CUSTOM step 설명), `version`(낙관적 락, FOR UPDATE 직렬화의
   defense-in-depth) 추가. `(pipeline_id, sequence)` 유니크 명시.
   - **왜**: `entity/Task.java`의 실제 컬럼 구성. 특히 `kind` 컬럼은 애초에 만들어진 적이 없다.
3. **관찰 테이블 머리말**: "only the latest row is read"를 "`task_attempt`의 최신 행만 읽는다;
   `task_check`/`terraform_result`는 write-only"로 정밀화.
   - **왜**: invariant 1의 실제 의미를 세 테이블 체제에 맞게 좁혔다(`ObservationRecorder`는
     유일한 기록자, 엔진 읽기는 `currentAttempt` 하나).
4. **`task_attempt` 행** (A-5 부수): `attempt_no` → `attempt_number` (3곳).
5. **`task_check` 행** (A-5): 존재하지 않는 `last_response_code`, `last_response_summary` 컬럼
   삭제. 나머지 서술(TF는 in-place UPDATE, condition은 폴당 insert, 원시 payload는
   `task_attempt.response`)은 `TaskCheck.java`/`ObservationRecorder.recordCheck`와 일치 확인.
6. **`terraform_result` 행 신설** (A-6): 컬럼, 기록 시점(attempt 판정 turn, finished job당 1행),
   16MB tail-우선 절단 + `truncated`, 본문 실패 시 `result_path` 포인터 행,
   `(task_id, attempt_number, job_id)` 유니크의 재실행 멱등, write-only 성격, 설계 문서 링크.
7. **Relationships**: `task_attempt 1:0..N terraform_result` 추가.
8. **Observation invariants**: #2에 `terraform_result`는 (attempt, job)당 1행·유니크 키로 dedup,
   #3에 "`terraform_result` 유실은 로그(진단) 손실일 뿐 상태 손실이 아님" 추가.
   - **왜**: 새 테이블이 기존 3대 불변식 안에서 어디에 위치하는지 없이는 §3의 논증이 미완이다.

### Enums 표

- **변경** (A-4): `TaskKind` 행 삭제(본문으로 대체 — "kind는 의도적으로 enum이 아니다: 열린
  mechanism/`TaskType` 이름 집합, 부팅 검증, `task_name`으로 영속"). `PipelineType`에 `CUSTOM`,
  `ErrorCode`에 `UNKNOWN_TASK` 추가. `TaskOperation`을 "conditional sixth enum" 각주에서 표의
  정식 행(closed set, 값이 mechanism을 소유)으로 승격. 카탈로그 enum(`TaskDefinition`,
  `RecipeDefinition`)이 상수 이름 문자열로 영속되어 삭제/rename 시 `UNKNOWN_TASK`로 열화한다는
  규약 추가.
- **왜**: `PipelineType.java`(CUSTOM), `ErrorCode.java`(UNKNOWN_TASK)가 이미 영속 값으로
  존재한다 — enum 표는 "canonical values"를 자처하므로 실제 값과 다르면 문서 전체의 신뢰를
  깎는다. `UNKNOWN_TASK`는 카탈로그 열화 규약의 짝이라 함께 설명해야 의미가 통한다.

### Links / Glossary

- **Glossary**: "Current task"의 `seq` → `sequence` 표기만 변경. PENDING pipeline 정의는 구현과
  일치해 유지. Links 변경 없음(terraform_result 설계 문서 링크는 Schema 행에 인라인).

---

## ADR-021 (실행 모델)

### Status

- **변경**: revision 한 줄 추가 — "alignment pass: index/SQL wording matched to the MySQL 8
  implementation, `active_target` slot release documented".
- **왜**: ADR-016과 동일 — 설계 변경이 아닌 문서-구현 정렬임을 이력으로 남긴다.

### Context / Guarantees 표

- **변경 없음.** 두 concurrent-writer 사실, 운영 사실, guarantee→mechanism 매핑 모두 구현과
  일치함을 확인했다(특히 "No terminal resurrection" 행의 Case A/B + tx1 서술은 LIN-30 구현
  그대로다).

### Decision 1 — Workers pull work from the DB

- **변경 없음.** 구현 확인: 자체 서버, `workerPerPod`개 drain을 sweep마다 던지는
  `PipelineScheduler`, 리더 없음, 프로세스 수 무관 정합성.

### Decision 2 — Pipeline-level claim via FOR UPDATE SKIP LOCKED + lease

- **변경**: claim SQL 블록 바로 아래에 **엔진 note** 한 단락 추가 (B-4):
  "이 ADR의 SQL은 PostgreSQL 문법의 설명용 의사 SQL이다. 실제 구현은 MySQL 8 + JPA/Hibernate —
  claim은 `PESSIMISTIC_WRITE` + lock-timeout `-2` 힌트로 `FOR UPDATE SKIP LOCKED`로 렌더링되고
  (테스트용 H2는 일반 `FOR UPDATE` 폴백), 스키마는 엔티티 어노테이션에서 생성되며 수기
  마이그레이션이 없다."
- **왜**: B-1 회귀의 근본 원인이 "ADR의 SQL이 어느 엔진 전제인지"가 문서에 없던 것이다.
  전제를 못박아 두면 다음에 다른 repo에서 문구를 이식할 때 같은 회귀가 재발하기 어렵다.
  claim 술어·`ORDER BY next_due_at`·SKIP LOCKED·per-claim UUID fencing token·PENDING→RUNNING
  tx1 원자 전이 서술 자체는 `PipelineRepository.lockClaimableDuePipelines`/`PipelineClaimer`와
  정확히 일치해 손대지 않았다.

### Decision 3 — Two-transaction split

- **변경 없음.** `PipelineWorker`가 트랜잭션을 열지 않고 claim(tx)/외부 호출/report(tx)를 잇는
  구조 그대로다.

### Decision 4 — Guarded write-back

- **변경**: tx2 고정 순서의 (4)단계에 한 구절 추가 (B-3): "종단 pipeline 전이는 같은 쓰기에서
  `active_target`도 비워 per-target uniqueness 슬롯을 해제한다(ADR-016 §4)."
- **왜**: `StepReporter.terminalize`가 DONE/FAILED/CANCELLED 수렴 시 `status`와 함께
  `active_target = NULL`, `last_activity_at`을 쓴다. 이 해제가 tx2 원자성 안에 있다는 사실은
  "종료 즉시 같은 target의 새 실행을 만들 수 있다"의 근거라 실행 ADR에 있어야 한다.
  나머지(토큰 단독 소유권 가드, status 가드 불필요 논증, BLOCKED 승격, `next_due_at` 전진과
  claim 해제, 중복 외부 호출의 3개 창)는 구현과 일치해 그대로다.

### Decision 5 — Crash recovery via lease expiry

- **변경 없음** (이 절 자체는). lease 하한 부등식은 유지하고, 그 집행에 관한 사실은 Knobs 절에
  기술했다(아래 참조).

### Decision 6 — Cancel: immediate / cooperative

- **변경**: Case A의 A1 SQL을 실제 구현 형태로 갱신 (B-2) —
  `SET status = 'CANCELLED', active_target = NULL, claimed_by = NULL, claimed_until = NULL,
  last_activity_at = now()`. 주석도 "uniqueness 슬롯 해제(ADR-016 §4)"를 언급하도록 확장.
- **왜**: `PipelineRepository.cancelIfIdle`의 실제 UPDATE다. `active_target = NULL`은 장식이
  아니라 **취소 직후 같은 target 재실행 생성을 가능하게 하는** 필수 동작인데 기존 스니펫에는
  빠져 있었다. 가드(`status IN ('RUNNING','PENDING') AND claim null/만료`)와 Case B
  (`cancel_requested=true, next_due_at=now`, `RUNNING`만), race-free 논증(3개 경합 케이스)은
  구현·주석과 일치해 유지했다.

### Safety mechanisms & tuning knobs

- **변경 1 — claim-predicate 인덱스** (B-1, **PR #27이 도입한 회귀의 원복**):
  - 이전(PR #27): "partial btree index on `(next_due_at) WHERE status IN ('RUNNING','PENDING')`".
  - 이후: "btree index on `(status, next_due_at)` — 선두 `status` 컬럼이 `IN` 필터를 감당한다.
    (MySQL 8은 partial index가 없어 `WHERE status IN (...)` 인덱스는 선택지가 아니다.)"
    admission cap 카운트를 받치는 `(claimed_until)` 인덱스 한 문장도 추가.
  - **왜**: 실제 인덱스는 `Pipeline.java`의 `idx_pipeline_claim (status, next_due_at)`와
    `idx_pipeline_claimed_until`이다. PR #27 **이전** 문구가 구현과 정확히 일치했는데,
    pii-agent-demo(PostgreSQL 전제)의 문구를 이식하며 덮어써 회귀했다. 사실상 원복 + 근거 명시.
- **변경 2**: 뒤따르는 DB polling 절의 "claim-predicate **partial** index" 참조에서 "partial"
  삭제(같은 회귀의 잔재).
- 나머지 bullet(worker count, lease tuning, slotCap 유보, polling 부하 제어)은 구현
  (`PipelineScheduler`의 geometric backoff + jitter + nearest-due 상한)과 일치해 유지.

### Decision 7 — Admission control and TF slot gate

- **변경**: 개념 이름 첫 등장에 실제 설정 키를 병기 — `runningPipelineCap`
  (`pipeline.execution.running-pipeline-cap`), `slotCap`(`terraform-slot-cap`,
  retry는 `terraform-slot-retry`). TF slot 점유의 **측정 근거** 한 문장 추가: "점유는
  `consumes_terraform_slot` 플래그가 선 `IN_PROGRESS` task 수로 센다(ADR-016 Schema)."
- **왜**: 본문의 camelCase 이름은 개념 이름으로 유지하되(문서 전반에서 쓰임), 운영자가 설정
  파일에서 찾을 실제 키와의 매핑이 없었다(B-5). slot 점유 카운트 방식은
  `PipelineWorker.terraformSlotAvailable()`
  (`countByConsumesTerraformSlotIsTrueAndStatus(IN_PROGRESS)`)이 구현한 사실인데, ADR-016
  Schema에 새로 문서화된 `consumes_terraform_slot` 컬럼과 연결해야 게이트 서술이 완결된다.
  soft-cap 의미론(count-read, M+C−1 overshoot 허용, QUEUED 상태 없음, slot 부족 시 READY 유지 +
  `next_due_at = now + slotRetry` + claim 해제)은 구현과 일치해 유지했다.

### Considered Options / Consequences / Worker-loop pseudocode

- **변경 없음.** pseudocode의 흐름(claim → cancel check → slot gate → 외부 호출 → report)은
  `PipelineScheduler.drain` + `PipelineWorker.process`와 구조적으로 일치한다(구현은 sweep 단위
  drain이지만 pseudocode는 "illustrative"로 명시돼 있어 그대로 둠).

### Operational reference — Knobs

- **변경** (B-5): 표를 실제 바인딩 키(`pipeline.execution.*` kebab-case)로 전면 갱신.
  - `slotCap`/`slotRetry` → `terraform-slot-cap`/`terraform-slot-retry`.
  - 구현에 있는데 표에 없던 knob 추가: `poll-interval`(일감 있을 때 sweep 간격),
    `scheduler-initial-delay`(부팅 후 첫 sweep 지연).
  - `activePodCount`/`maxReplicas`는 표에서 빼고 "pod topology는 배포(k8s) 소유이지 앱 설정이
    아니다 — totalWorkerCount와 overshoot 상한의 인자로만 등장한다" 단락으로 분리.
  - 하한 부등식 아래에 "집행 가능한 핵심(`lease-duration > api-call-timeout`)은 부팅 시
    fail-fast로 검증된다; queue wait/margin은 운영 튜닝으로 남는다" 추가.
- **왜**: `ExecutionSettings.java`(record + compact constructor 검증)와
  `application.yml`의 실제 키·검증 로직이다. 특히 fail-fast 검증은 Decision 5의 하드 제약이
  "문서 속 권고"가 아니라 코드가 집행하는 계약임을 밝히는 정보라 추가할 가치가 있다.

### Operational reference — Key metrics

- **변경 없음.** 이 목록은 참조 카탈로그(비규범)이고, RUNNING/PENDING 분리 카운트 서술은
  Admin 통계 구현(`LivePipelineStatistics`, `PipelineStatistics`)과 일치한다.

### Links / Glossary

- **변경 없음.** "Due pipeline"(비종료 + due + lease 만료), "Claim", "Cooperative cancel",
  "Guarded write-back", "Lease", "Two-transaction split" 정의 모두 구현과 일치 확인.

### Revision history

- **변경 1**: 기존 2026-07-03(LIN-30) 항목의 "Claim predicate **and its index** widen" 문구
  수정 — 인덱스는 넓어진 게 아니라 복합 `(status, next_due_at)` 인덱스가 두 상태를 그대로
  감당한다.
  - **왜**: 이력 항목 자체가 스키마를 잘못 서술하고 있었다(B-1과 같은 뿌리). 이력의 취지
    ("무엇이 바뀌었나")는 유지하고 사실 관계만 교정했다.
- **변경 2**: alignment pass 항목 신설 — 인덱스 원복과 그 경위(PostgreSQL 문구 이식), 엔진
  note, `active_target` 해제 문서화, slot 점유 근거, knob 정렬을 요약.

---

## 반영하지 않은 것 (의도적)

- **메트릭 카탈로그 축소/구현 여부 표기** — ADR-021이 스스로 "reference catalog only"라
  선언하므로, 미구현 메트릭을 지우는 것은 카탈로그의 목적(운영 시 잴 것들의 위시리스트)과
  어긋난다.
- **worker 루프 pseudocode를 sweep/drain 구조로 재작성** — "illustrative"로 명시된 코드라
  구조 일치까지 요구하지 않았다. 안전지점·게이트·release 규칙 등 규범적 서술은 모두 일치한다.
- **ADR-016 Context의 "~12 pipeline shapes"** — 카탈로그 recipe 규모의 서술로 여전히 유효하다.
  CUSTOM은 §2와 Enums에서 다룬다.
