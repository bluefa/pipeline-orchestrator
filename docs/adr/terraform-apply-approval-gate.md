# ADR: Terraform Apply 승인 게이트 — plan 확인 후 진행(ChatOps 포함)

## 상태

제안됨 — 2026-08-22 (개정 2026-08-22 10차: main의 재시작 기능(#42) 병합 반영 — 개정 이력 참조).

[ADR-016](016-install-delete-pipeline-domain-model.md)(도메인 모델)·
[ADR-021](021-pipeline-execution-model.md)(실행 모델)·
[ADR-022](022-terminal-state-notification.md)(종단 알림)의 **후속**으로, terraform
PLAN의 결과를 **사람이 확인한 뒤에만 APPLY가 진행**되도록 하는 승인 게이트와, 그
승인을 Slack에서 수행하는 ChatOps 경로를 정한다.

**Phase 1 범위(오너 확정, 2026-08-22)**:

- **승인 주체는 관리자만.** 콘솔 승인 API는 관리자 권한으로 한정하고, Slack 승인은
  관리자 전용 비공개 채널에서만 받는다. 서비스 담당자로의 확대는 Phase 2 후보.
- **게이트 대상은 `AWS_SERVICE_TF_APPLY`·`GCP_SERVICE_TF_APPLY` 두 operation만.**
  BDC 계열(COMMON/SERVICE_LEVEL/BDC)·Azure·IDC는 Phase 1 범위 밖.
  **DELETE(destroy) 게이트는 설계를 이 ADR에 확정하되(§결정 9, 오너 지시
  2026-08-22) 구현은 후속 단계다** — destroy plan operation 신설과 InfraManager
  협의가 선행 조건이라 롤아웃 PR 4로 분리한다.
  **custom recipe에는 승인 operation을 허용하지 않는다** — custom 생성 검증이
  승인 게이트 배치를 명시적으로 거부하고, **task catalog 응답에서도 승인 정의를
  제외**한다(현 catalog는 `TaskDefinition.values()` 전체를 노출하므로, 제외하지
  않으면 UI 선택지에 나타났다가 제출 시에만 거절되는 어긋난 표면이 생긴다).
  카탈로그 레시피 두 곳뿐이 Phase 1 표면이다.

ADR-016/021의 불변식("DB row가 곧 상태", at-least-once + 멱등성, 종단 상태 부활
금지, 개념 최소화)과 ADR-022의 PII payload 계약을 그대로 상속한다. 단 하나의
명시적 예외(§결정 5 — `terraform_result`의 표시 전용 읽기)를 이 ADR이 소유한다.

## 맥락

### PLAN 결과를 아무도 보지 않고 APPLY가 진행된다

현재 레시피(ADR-016)는 `*_TF_PLAN` task가 `DONE`이면 다음 task인 `*_TF_APPLY`가
자동 승격된다. plan이 무엇을 만들고 무엇을 **destroy**하는지 사람이 확인하는 지점이
파이프라인 어디에도 없다. 인프라 변경(특히 destroy/replace)은 되돌리기 비싼
작업이므로, 적용 전에 담당자 확인을 받으라는 운영 요구가 있다.

세 가지 사실이 설계 공간을 정한다.

- **plan 원문은 이미 저장돼 있다.** PLAN task가 verdict에 도달하는 시점에
  `TerraformResultRecorder`가 로그 전문을 `terraform_result`(MEDIUMTEXT, 최대
  400만 자, 초과 시 tail 보존)에 남긴다 — 새 수집 경로가 필요 없다. 단 이 데이터는
  **best-effort 관측**이다(조회·저장 실패는 삼켜지고, 본문이 null이거나 절단될 수
  있다). 이 성질이 §결정 5의 fail-closed 규칙을 강제한다.
- **그러나 원문은 사람이 리뷰할 수 있는 형태가 아니다.** 수백~수천 줄 로그를
  승인자에게 그대로 들이밀 수 없다 — 요약이 필요하다.
- **배포 환경은 private cluster다.** 인터넷에서 서버로 들어오는 경로가 없다.
  Slack 승인(버튼 클릭 → 서버 통지)은 **아웃바운드 연결만으로** 성립해야 한다.

### 규모

ADR-016/021과 동일(대상 ~2,000개, 단일 조직 내부 도구). 승인 빈도는 파이프라인
생성 빈도 이하이므로, 별도 승인 서비스·워크플로 엔진을 정당화하지 않는다.

## 결정

### 1. 승인은 신규 게이트 task로 모델링한다

PLAN과 APPLY 사이에 **승인 게이트 task**를 레시피로 삽입한다(DELETE 레시피의
게이트 위치는 §결정 9). `TaskOperation`에
실행 단위 무관 단일 값 **`TF_APPLY_APPROVAL`**(mechanism=APPROVAL, terraform slot
미사용)을 추가한다 — 비-terraform mechanism의 op 선례는 `NETWORK_READY`
(CONDITION_CHECK)다. 단, op가 단일이어도 **`TaskDefinition`은 provider 스코프**라서
(`RecipeCatalog`가 부팅 시 step.provider == recipe.provider를 검증) 게이트 정의는
**AWS용·GCP용 2개**가 필요하다. `NETWORK_READY_V1`도 실제로는 AWS 스코프로
`AWS_INSTALL_V1` 한 곳에만 쓰인다 — "여러 레시피 재사용" 선례가 아니다.

- ADR-021의 "현재 task = 최저 sequence 비종결 task" 규칙이 그대로 성립한다: PLAN이
  `DONE`이 되는 순간 게이트가 current task가 되고, 승인되어 게이트가 `DONE`이 되면
  APPLY가 승격된다. **상태 머신·감사 이력(TaskAttempt)을 재사용**하며 새 실행
  개념이 없다.
- PLAN 성공과 승인은 별개 관심사다 — PLAN task에 승인 상태를 덧붙이는 안(대안 B)은
  "PLAN은 성공했는데 task는 미완료"라는 모순 상태를 만들고 PLAN 재시도 로직과
  승인 로직을 한 task에 얽는다. 기각.

**레시피 버전 전환(부팅 제약 반영).** `RecipeCatalog`는 `(provider, pipelineType)`당
레시피를 정확히 하나만 허용한다(중복 발견 시 부팅 실패). 따라서 V2를 V1과 카탈로그에
**병존시킬 수 없다** — 다음 규칙으로 도입한다:

- `RecipeDefinition`에 게이트 포함 레시피를 추가한다 — 명명은 **`_V2`가 아니라
  `WITH_ADMIN_CONSENT`다(오너 명명, 2026-08-22)**:
  `AWS_INSTALL_WITH_ADMIN_CONSENT_V1`/`GCP_INSTALL_WITH_ADMIN_CONSENT_V1`.
  게이트 유무는 내용의 "다음 버전"이 아니라 **병렬 변형**이므로 의미를 이름에
  싣고, `_V1` suffix는 유지한다(레시피 이름-버전 불변 규약 — `_V2`는 내용
  진화용으로 남겨둔다). 축 이름은 코드의 `PipelineType`(INSTALL/DELETE)을
  따른다. **카탈로그의 활성 등록은 (provider, type)당 하나를 유지**한다: 활성
  레시피 선택이 `pipeline.approval.enabled`를 따른다(`false`→기존
  V1, `true`→WITH_ADMIN_CONSENT). 선택은 부팅 시 고정이며(설정이 부팅
  고정이므로), 어느 쪽이든 카탈로그 중복 검사는 그대로 성립한다. Phase 2에서
  승인 주체가 담당자로 확대되어도(§결정 7) 레시피 이름은 write-once라 유지한다 —
  이름의 `ADMIN`은 "관리자 동의 게이트로 설계됨"의 이력이고, 실제 승인 권한은
  §결정 7의 정책 소관이다.
- **이를 위해 `RecipeCatalog` 생성자 수정이 필수다.** 현 생성자는 설정 주입 없이
  `RecipeDefinition.values()` **전체를 무조건 등록**하므로, WITH_ADMIN_CONSENT
  상수를 추가하는 순간 `approval.enabled` 값과 무관하게 (provider, type) 중복으로
  부팅이 깨진다.
  `ApprovalSettings`를 주입받아 비활성 버전을 등록에서 거르는 필터가 PR 1의
  명시적 수정 지점이다.
- **비활성 레시피는 이름 해석 전용이다.** `RecipeDefinition.find(name)`은 양쪽
  이름을 계속 해석한다 — 진행 중/과거 파이프라인 행(`recipe_definition` 문자열)의
  표시·검증이 깨지지 않는다. 신규 생성만 활성 버전을 쓴다. 진행 중 V1
  파이프라인은 비파괴로 완주한다(recipe는 write-once).

### 2. 상태는 `AWAIT_APPROVAL`을 Task/Pipeline 양쪽에 추가한다

상태값 이름은 **`AWAIT_APPROVAL`**(14자)이다 — task/pipeline의 status 컬럼이
`VARCHAR(16)`이고 hbm2ddl update는 기존 컬럼 길이를 변경하지 않으므로,
`AWAITING_APPROVAL`(17자)은 저장 자체가 불가하다. 컬럼 확장(수기 DDL 금지 —
AGENTS.md) 대신 16자 이내 이름을 택한다. 콘솔 표시는 어차피 라벨 매핑(`승인 대기`)
이므로 wire 값 길이는 UX에 무관하다.

- `TaskStatus`에 `AWAIT_APPROVAL`(비종결) 추가. 게이트 task 전용: 디스패치 시
  `IN_PROGRESS` 대신 이 상태로 전이한다.
- `PipelineStatus`에도 `AWAIT_APPROVAL`(비종결) 추가. 게이트가 대기에 들어가면
  파이프라인도 이 상태가 된다 — 대시보드 리스트·"확인 필요" 집계가 파생 로직 없이
  상태만으로 성립한다.
- 상태 쓰기는 기존 소유 컴포넌트를 그대로 따른다 — **task 전이는
  `TaskStateMachine`**(승격은 `StepReporter`, 취소는 `TaskCanceller` — 현행 그대로),
  **pipeline 전이는 write-back(`StepReporter`)·claim(`PipelineClaimer`)·idle-cancel
  질의**가 쓴다. `TaskStateMachine`은 `Pipeline`에 접근하지 않으므로(현 코드 사실)
  "task+pipeline 동시 전이"를 TaskStateMachine 한 곳에 몰 수 없다. 추가되는 전이:

| 전이 | 조건 |
|---|---|
| `READY → AWAIT_APPROVAL` (task+pipeline) | 게이트 디스패치 — 승인 행 생성과 함께 **write-back 트랜잭션에서 커밋**(외부 호출 없음, §결정 6) |
| `AWAIT_APPROVAL → DONE` (task) | 승인 확인(승인 행 `APPROVED`) — 다음 successor 승격(install 게이트=APPLY, DELETE 게이트=첫 destroy step — §결정 9) |
| `AWAIT_APPROVAL → CANCELLED` (task+pipeline) | 반려(승인 행 `REJECTED` + `cancel_requested` 설정 — 기존 취소 경로로 수행, §결정 2 실행 계약) 또는 파이프라인 취소 — 의도된 중단이지 오류가 아니므로 `FAILED`가 아니다 |
| `AWAIT_APPROVAL → FAILED` (task), `ErrorCode.APPROVAL_EXPIRED` | 만료 — 워커의 `REQUESTED→EXPIRED` CAS 성공 시에만(§결정 3). 비재시도 |

**파급 명세 — `RUNNING`/`PENDING`을 하드코딩한 경로 전부가 수정 대상이다.**
"claim 술어 확장 외 수정 없음"이 아니다. 채택하는 방식은 **PENDING 선례의
부분 재사용**이다: claim UPDATE가 후보 상태와 무관하게 `RUNNING`으로 flip하는
기존 동작(`PipelineClaimer`)을 그대로 타므로, `AWAIT_APPROVAL`을 claim 후보
스캔에 포함하면 재개 방향(`AWAIT_APPROVAL→RUNNING` flip과 그 뒤의 승격·재계산)은
공짜다. **단, 선례는 재개 방향에만 성립한다** — PENDING은 claim 시점에만
존재하는 상태지만 `AWAIT_APPROVAL`은 **write-back이 끝나는 순간의 상태**라서,
`releaseClaim`·`promoteBlockedSuccessor`의 `status == RUNNING` 전용 가드가 게이트
진입 write-back에는 타지 않는다. 진입 시 `next_due_at = expires_at`을 명시
대입하지 않으면 직전 스텝이 남긴 과거 값이 그대로 남아 **파이프라인이 매
sweep(10초)마다 재claim된다** — "대기 중 자원 미점유" 약속이 깨진다. 수정 지점
목록:

| 경로 | 수정 |
|---|---|
| `PipelineRepository` claim·nearest-due·idle-cancel 질의(RUNNING/PENDING 하드코딩 3곳) | 후보 상태에 `AWAIT_APPROVAL` 추가 |
| `PipelineClaimer`의 무조건 `RUNNING` flip | 무변경(위 선례가 흡수) |
| `PipelineControl` 취소 Case A/B | `AWAIT_APPROVAL` 포함 — 대기 중 cancel이 no-op이 되지 않게. 취소 경로는 승인 행도 `REQUESTED→CANCELLED` CAS(§결정 4)로 닫는다 |
| `StepReporter` — **게이트 진입 분기 추가** | `releaseClaim`은 `RUNNING`일 때만 `next_due_at`을 재계산하므로 "무변경"이 아니다. 게이트 진입 write-back에서 pipeline `AWAIT_APPROVAL` 전이 + **`next_due_at = expires_at` 명시 대입**을 수행한다(실행 계약 절). 승인 후 재개 방향(claim이 RUNNING flip한 뒤의 승격·재계산)은 무변경 |
| `TaskStateMachine.markInProgress`의 `DispatchResult` 처리 | 단일 `instanceof`(WithResponse)라 **새 변형이 컴파일 에러 없이 else로 떨어져 `IN_PROGRESS`가 된다** — exhaustive `switch` 패턴 매칭으로 교체(침묵 회귀 차단) |
| `TaskExecutionSpec` 팩토리(`terraform`/`conditionCheck` 2종뿐) | APPROVAL mechanism용 `approvalGate(...)` 팩토리 추가 |
| `PipelineQueryService` current task 판정(READY/IN_PROGRESS) | `AWAIT_APPROVAL` 포함 |
| `PipelineQueryService` 기간 통계 + `PipelineStatistics` DTO | **`await_approval_count` 필드 추가(PR 1)** — 응답이 "상태별 필드 + 그 합 = total" 구조이므로 total에만 더하면 노출된 상태 합과 total이 어긋나고, 기존 필드에 합치면 의미가 왜곡된다. 실시간 통계 DTO의 "확인 필요" 수치 확장은 콘솔 요구에 따라 PR 2에서 |
| `SlackNotifier`(ADR-022 종단 알림)의 exhaustive `MessageStyle` switch | 비종단 `AWAIT_APPROVAL` 분기 추가(비종단 throw 유지) — 컴파일러가 잡지만 파급 목록에 명시해 둔다 |
| admission cap(`runningPipelineCap`) | 무변경 — cap은 상태가 아니라 **활성 claim 수**(`claimed_until > now`)를 세고, 대기 파이프라인은 claim이 없으므로 소비하지 않는다(유지해야 할 성질) |
| `PipelineRestarter`(#42) | 재시작 가능 상태가 `FAILED`/`CANCELLED` **양성 목록**이라 `AWAIT_APPROVAL`은 자동 배제된다(대기 중 재시작은 거절 — 취소가 먼저). 단 만료된 게이트의 재시작 지점·요청 맥락 승계는 조정 대상(§결정 3) |

**실행 계약 파급 — 게이트는 sealed 결과 경로에 자기 변형을 추가해야 한다.** 상태
enum 확장만으로는 게이트가 돌지 않는다. 다음이 PR 1의 실행 계약 작업이다:

- **`TaskType` 구현**: mechanism `APPROVAL`에 대응하는 `ApprovalGateTask`를
  `TaskTypeRegistry`에 등록한다(미등록 mechanism은 부팅 검증 실패 — 기존 완결성
  검사가 그대로 지켜준다).
- **`StepRunner` 상태 분기**: `READY →` 게이트 execute(승인 행 생성은 write-back
  트랜잭션으로 넘길 데이터만 준비 — 외부 호출 없음), `AWAIT_APPROVAL →` 게이트
  check(§결정 3의 CAS 판정으로 이어짐). 기존 switch는 BLOCKED(unblock)·READY·
  IN_PROGRESS·종단(throw)을 exhaustive하게 다루므로 `AWAIT_APPROVAL` 분기 추가가
  필요하다 — 새 enum 값은 컴파일 에러로 강제된다.
- **sealed 결과 변형 — 진입은 dispatch 계약, 판정은 check 계약에 얹는다.**
  `execute()`의 반환형은 `DispatchResult`, `TaskProgress`는 `check()`의
  반환형이다(실코드 계약). 따라서 게이트 진입 변형은 **`DispatchResult`에**
  추가한다: `AwaitApproval(expiresAt, planSummaryJson)`. 실제 전달 경로는 현
  코드 구조를 따른다 — `DispatchResult`는 `StepOutcome.Dispatched`에 싸여
  write-back에 도달하고, `TaskStateMachine`의 dispatch 분기가 `AwaitApproval`이면
  `IN_PROGRESS` 대신 **task `AWAIT_APPROVAL` 전이 + 승인 행 INSERT + attempt
  시작**(기존 Dispatched와 동일하게 — 게이트의 요청~종결이 TaskAttempt 감사
  이력에 남는다)을 수행하며, **pipeline `AWAIT_APPROVAL` 전이 +
  `next_due_at=expires_at`은 pipeline 행 소유자인 `StepReporter`(write-back)**가
  같은 트랜잭션에서 기록한다.
- **판정은 run 단계가 아니라 write-back이다 — ADR-021 계약의 명시적 예외.**
  `check()`는 트랜잭션 밖에서 돌므로(StepRunner 구조) 거기서 CAS를 실행하면
  §결정 3이 확보한 원자성(CAS와 전이가 한 트랜잭션)이 되돌아간다. 따라서 게이트는
  run 단계에서 판정하지 않는다: `StepRunner`의 `AWAIT_APPROVAL` 분기는 게이트
  check를 호출하지 않고 **판정 위임 변형 `StepOutcome.ApprovalPoll`**(무payload)을
  반환하며, `applyOutcome`의 exhaustive switch에 추가된 분기가 **write-back
  트랜잭션 안에서** §결정 3의 만료 CAS·재독을 수행해 전이를 고른다 — 승인
  확정=`DONE`, 만료 CAS 성공=`FAILED(APPROVAL_EXPIRED)`, 아직
  유효(`REQUESTED`이고 `expires_at > now` — 이른 웨이크업)=대기 유지 +
  `next_due_at = expires_at` 재설정. "run 단계 결과를 write-back이 그대로
  적용한다"는 ADR-021 계약의 예외이며, 판정 입력이 DB 행 자체라서 생기는
  구조적 예외임을 여기 선언한다.
  **반려는 판정 변형이 아니라 취소 요청 경로다**(아래).
- **반려의 값 경로 = 기존 `cancel_requested` 재사용.** `TaskProgress`/
  `StepOutcome`에 취소 변형이 없고 전체 취소는 `cancel_requested`를 관찰하는
  기존 경로뿐이므로, 반려는 그 경로에 태운다: `decide(REJECT)`가 같은
  트랜잭션에서 `REQUESTED→REJECTED` CAS와 함께 **`pipeline.cancel_requested =
  true`**를 세우고 웨이크업한다. claim된 워커는 기존 취소 처리(현재 task 종결 →
  파이프라인 `CANCELLED`)를 그대로 수행한다 — 반려를 위한 별도 outcome 변형이나
  `StepReporter` 확장은 없다(게이트 자체의 `ApprovalPoll` 변형·진입 분기와는
  별개다). `cancel_requested`는 상태 전이가 아니라 기존 취소 API도 세우는
  도메인 요청 플래그이므로 "decide()는 전이를 쓰지 않는다" 불변식과 충돌하지
  않는다(불변식 1에 명시). 만료 check의 재독에서 `REJECTED`를 발견하는 경우에도
  `cancel_requested`가 이미 세워져 있어 같은 경로로 수렴한다.

### 3. 대기·웨이크업·만료는 `next_due_at`으로 처리한다 — 만료는 CAS가 승자를 정한다

ADR-021의 claim 술어(`next_due_at <= now`)를 그대로 쓴다.

```
디스패치:  pipeline.next_due_at = 승인 만료 시각(now + timeout)   -- 만료까지 claim 안 됨
결정 직후: pipeline.next_due_at = now                              -- 다음 sweep이 즉시 claim
만료:      아무도 안 누르면 만료 시각에 claim이 돌아옴
```

**승인과 만료의 race는 `task_approval.status`의 CAS로 원자적으로 판정한다.** 게이트
check는 트랜잭션 밖에서 읽으므로(StepRunner의 기존 구조) 읽은 값은 stale일 수
있다 — 따라서 **판정은 읽기가 아니라 write-back 트랜잭션 안의 CAS**로 한다:

- 워커의 만료 처리: write-back 트랜잭션(파이프라인 행 잠금 하)에서
  `UPDATE task_approval SET status='EXPIRED' WHERE task_id=:id AND
  status='REQUESTED' AND expires_at <= :now` — **1행이면 만료 확정**(task FAILED).
  0행이면 재독으로 구분한다: 결정이 이미 커밋됐으면(APPROVED/REJECTED/CANCELLED)
  그 결정대로 전이하고, **아직 `REQUESTED`이고 `expires_at > now`이면(이른
  웨이크업) 대기를 유지하며 `next_due_at = expires_at`을 재설정한다**(§결정 2
  실행 계약의 `ApprovalPoll` 분기).
- `decide()`의 승인/반려 CAS는 `status='REQUESTED' AND expires_at > :now`를
  조건으로 한다(§결정 4) — **만료 시각이 지난 뒤에는 승인이 이길 수 없다.**
  만료 CAS의 `expires_at <= :now` 가드는 이 조건의 **정확한 여집합**이다:
  시계는 앱 `Clock`이지만 파드마다 로컬이므로(멀티 파드), 시간 가드 없는 만료
  CAS는 시계가 몇 초 빠른 파드가 **아직 유효한 승인 창을 조기 폐쇄**할 수 있다 —
  가드가 있으면 어느 파드가 먼저 오든 두 CAS가 상보적으로 판정된다.

기존 start-delay(PENDING + 미래 `next_due_at`)와 같은 형태라 `PipelineScheduler`는
무변경이고(§결정 2의 술어 확장은 repository 질의), 만료는 MutableClock으로
테스트한다. 만료 시간은 env `pipeline.approval.timeout`(기본 **PT24H**)이다 —
운영이 조정할 값이므로 코드 상수가 아니라 설정이다(ADR-022의 기준과 동일).
만료 후 재요청은 **기존 재시작(restart) 경로를 그대로 쓴다**(#42로 main에 병합됨 —
`PipelineRestarter`·`POST …/restart`·`origin_pipeline_id`/`origin_task_id`).
승인 게이트 전용 재요청 API를 만들지 않으며, 종단 task의 부활(ADR-016 금지)도
없다 — 재시작은 원본을 되살리는 것이 아니라 원본 체인의 suffix로 만드는 새
파이프라인이기 때문이다. 만료는 게이트 task `FAILED` → 파이프라인 `FAILED`로
닫히므로 재시작 가능 조건(최신 실행 + FAILED/CANCELLED)을 그대로 충족하고,
원본과의 계보는 write-once provenance 컬럼에 자동으로 남는다.

- **재시작 지점은 게이트가 아니라 그 앞의 PLAN task여야 한다(PR 1에서 확정).**
  기본 재시작 지점은 "원본 체인의 첫 non-DONE task"이므로 만료된 게이트 자신이
  되는데, 그러면 새 체인에 PLAN task가 없어 **요약의 원천인 `terraform_result`가
  새 파이프라인에 존재하지 않는다**(그 행은 원본 PLAN task에 붙어 있다) — 게이트는
  fail-closed 규칙(§결정 5)에 따라 수치 없이 "검증 불가"만 띄우고, 승인자는 판단
  근거 없이 버튼만 보게 된다. `fromSequence`는 **기본 지점보다 앞으로만** 허용되므로
  PLAN으로 당기는 것은 API 제약상 가능하다. 게이트가 기본 지점일 때 서버가 지점을
  PLAN까지 자동으로 당길지, `restart-preview`가 경고만 하고 호출자가 지정할지는
  PR 1에서 결정한다 — 어느 쪽이든 **"게이트만 재시작"은 허용하지 않는다**.
- **요청 맥락의 승계도 함께 정한다.** `PipelinePlan.restartOf`는 target·type·
  recipe·provider만 원본에서 승계하므로 §결정 4의 `requested_by`/`request_note`가
  새 파이프라인에 비어 버린다 — 게이트 레시피는 `requested_by`가 필수이므로 그대로
  두면 재시작이 400으로 막히거나 요청자 없는 승인 요청이 나간다. 원본에서 승계하되
  재시작 요청 본문으로 덮어쓸 수 있게 한다(재시작을 누른 사람이 새 요청자다).

### 4. 결정 기록은 `task_approval` 테이블 — 조건부 UPDATE가 멱등성을 보장한다

게이트 task당 정확히 1행(1:1, `task_id` unique)으로 승인 요청~결정의 전 생애를
기록한다(스키마 절). 행의 결정 전이는 전부 `REQUESTED`에서 출발하는 CAS다:

```
승인/반려: UPDATE task_approval SET status=:decision, decided_at=:now, approver_id=..., channel=...
            WHERE task_id=:taskId AND status='REQUESTED' AND expires_at > :now
만료(워커): SET status='EXPIRED'   WHERE task_id=:taskId AND status='REQUESTED' AND expires_at <= :now
취소 경로:  SET status='CANCELLED' WHERE task_id=:taskId AND status='REQUESTED'
```

공용 진입점 `ApprovalService.decide(taskId, decision, approverId, approverName,
channel)`은 **한 트랜잭션에서** 위 CAS와 웨이크업(`pipeline.next_due_at = now`)을
수행한다 — CAS가 1행일 때만 웨이크업하므로 유실도 이중 웨이크업도 없다. 반려는
같은 트랜잭션에서 `pipeline.cancel_requested = true`도 세운다(§결정 2 실행 계약 —
기존 취소 경로가 전체 취소를 수행).

- **락 순서는 pipeline → task_approval로 고정한다(불변식 6).** 워커의
  write-back은 트랜잭션 최초 문장이 파이프라인 행 `FOR UPDATE`이고 그 안에서
  승인 CAS를 실행한다(`StepReporter` 구조). decide()가 승인 CAS부터 잡으면 락
  순서가 반대(ABBA)가 되어, 만료 시각 부근에 워커와 decide()가 맞물리는 순간
  데드락 → 한쪽 롤백(워커면 write-back 유실 후 lease 만료까지 지연, decide()면
  ack 불가·Slack 재전송)이 된다. 따라서 **decide()도 트랜잭션 최초 문장으로
  pipeline 행을 `FOR UPDATE`로 잠근 뒤** 승인 CAS를 실행한다. 취소 경로는 이미
  pipeline-first라 맞출 것이 없다.
- **외부 유래 문자열은 경계에서 절단한다.** `approver_id`/`approver_name`은
  BFF·Slack payload에서 오는 값이라 길이를 통제할 수 없다 — 컬럼 상수(64자)
  기준으로 decide() 진입부에서 절단한다(표시 전용 값이라 절단 무해). 절단 없이
  넣으면 초과 1건이 판정 트랜잭션 전체를 롤백시켜 그 사용자는 **결정적으로 승인
  불가**가 된다(원장 R10 judgment-tx overflow 재발 패턴).

**요청 맥락 — 누가·왜 요청했는지는 파이프라인 생성 시점에 수집한다(오너 요구
2026-08-22).** 게이트 task는 레시피가 자동 생성하므로 승인 요청의 "요청자"는
곧 **파이프라인 생성자**다. 그런데 현 코드에는 생성자 identity가 없다
(`created_at` 시각뿐, 생성 API는 target+type만 받는다). 따라서:

- 생성 API에 **`requested_by`**(요청자 identity — 인가와 동일하게 **BFF가 검증된
  계정에서 주입**, 오케스트레이터는 기록만)와 **`request_note`**(확인 요청
  메시지 — 요청자가 승인자에게 남기는 자유 텍스트, 선택)를 추가하고 `pipeline`
  행에 저장한다(write-once). 게이트 레시피(WITH_ADMIN_CONSENT 활성) 생성에는
  `requested_by`가 필수다(누락 시 400) — 요청자 없는 승인 요청은 감사가 성립하지
  않는다. 비게이트 레시피에서는 선택(있으면 기록 — 일반 감사에도 유익).
- **두 필드의 경계 계약은 절단이 아니라 typed 400 거절이다**(사람이 쓴 값을
  말없이 자르면 의도가 훼손된다 — R10 규칙 "bound or reject"에서 reject 선택):
  `requested_by`는 non-blank·최대 64자(blank·초과 400 — blank를 허용하면 필수
  감사가 형식으로만 통과하고, 초과는 insert에서 500으로 새므로 경계에서 막는다),
  `request_note`는 최대 200자(custom task 설명 ≤100 선례와 같은 축, 초과 400).
  각각 안정적인 `OrchestrationErrorCode` 신규 값으로 거절한다. 수용 지점은 생성
  두 갈래(카탈로그 `CreatePipelineRequest`·custom `CustomPipelineRequest`)
  모두이며, `requested_by` **필수** 검증은 게이트 레시피가 활성인 카탈로그 생성
  조합에만 건다(custom은 게이트 배치 자체가 금지 — Phase 1 범위).
- 승인 요청 표시(Slack·콘솔)는 이 두 값 + `requested_at`을 함께 보여준다(§결정
  5·6). `task_approval`에 복사하지 않는다 — 진실원은 pipeline 행 하나이고, 발송
  sweep·조회가 조인해 읽는다(이중 저장 금지 — ADR-022 계약과 동일 원칙).

**0행의 의미는 둘이다** — 최신 행을 재독해 구분한다:

- 재독 결과가 `APPROVED`/`REJECTED`/`EXPIRED`/`CANCELLED` → 이미 결정된 것.
  기존 결정을 반환한다(Slack은 ephemeral "이미 ○○ 님이 처리했습니다").
- 재독 결과가 여전히 `REQUESTED`(즉 `expires_at <= now`인데 워커의 EXPIRED CAS가
  아직 안 돈 구간) → **"기한 경과 — 만료 처리 대기"**로 반환한다(Slack ephemeral
  "승인 기한이 지났습니다"). 이 구간의 승인은 시간 조건이 안전하게 차단하지만,
  응답을 "이미 처리됨"으로 단정하면 사실과 다르다 — 별도 결과로 구분한다.

콘솔 API(`POST /api/v1/pipelines/{id}/tasks/{taskId}/approve`·`/reject`)와 Slack
버튼 핸들러가 같은 `decide()`를 호출한다. 콘솔과 Slack 동시 클릭, 멀티 파드 중복
수신 모두 CAS가 최초 1건만 통과시킨다. **상태 전이는 decide()가 하지 않는다** —
결정 기록과 웨이크업뿐이고, task/pipeline 전이는 claim된 워커가 파이프라인 행
잠금 하의 write-back 경로(§결정 2의 소유 컴포넌트 구분)로 수행한다.

**신뢰 경계(Phase 1).** 이 오케스트레이터는 자체 인증이 없다 — 클러스터 내부에서
BFF(콘솔)만 바라보는 기존 API들과 같은 신뢰 모델이다(cancel API와 동일).

- **콘솔 경로**: 관리자 권한 강제와 `approver_id/name` 주입은 **BFF의 책임**이다 —
  BFF가 자기 세션에서 검증된 관리자 identity를 body에 채워 호출한다. 오케스트레이터는
  이 값을 기록만 하며, 이 계약(BFF 외 호출자 없음)은 네트워크 경계(내부망)가
  보증한다. 브라우저가 오케스트레이터를 직접 호출하는 경로는 존재하지 않는다.
- **Slack 경로**: 핸들러가 서버 측에서 검증한다 — payload의 channel이 설정값
  (`slack-channel-id`)과, team이 **부팅 시 `auth.test`로 확정한 workspace id**와
  일치하는지(설정 키를 늘리지 않는다 — 토큰이 곧 workspace를 결정하므로 부팅 시
  1회 조회·고정, 실패 시 fail-fast), action `value`의 taskId가 실제 `REQUESTED`
  승인 행의 게이트인지, message `ts`가 저장된 `slack_message_ts`와 일치하는지.
  "비공개 채널이라서"는 발송 반경의 통제일 뿐 핸들러 검증을 대체하지 않는다.

### 5. plan 요약은 백엔드가 저장된 로그에서 추출한다 — 표시 전용, fail-closed

게이트의 승인 요청 표시(콘솔 모달·Slack 메시지)를 위해 직전 PLAN task의
`terraform_result`에서 요약을 추출해 `task_approval.plan_summary`(JSON)에 캐시한다.
콘솔과 Slack이 같은 요약을 소비한다(파서 이중화 없음 — 프론트 파싱 기각, BFF는
verbatim proxy 규약).

**ADR-016 예외의 명시적 선언과 그 한계.** ADR-016 관측 불변식은 "엔진은
`terraform_result`를 읽지 않는다"이다. 이 ADR은 **표시 payload 조립에 한정한
읽기**를 유일한 예외로 선언한다 — 단 다음 두 규칙이 예외를 안전하게 만든다:

- **전이 판정은 결코 요약에 의존하지 않는다.** 게이트의 전이 입력은
  `task_approval`의 `status`·`expires_at`뿐이다(불변식 1). 요약 추출이 실패해도
  게이트는 정상 진입·정상 결정된다.
- **요약은 fail-closed다.** `terraform_result`는 best-effort다(행 누락·본문
  null·tail 절단 가능, 한 attempt에 여러 job). 직전 PLAN task의 **완료 attempt의
  모든 job** 결과가 존재하고, 각각 파싱이 성립하며(집계 라인과 수집된 리소스
  라인 수가 정합), 절단으로 앞부분이 잘리지 않았을 때만 `verified=true` 요약을
  만든다. **하나라도 어긋나면 요약 수치를 보여주지 않고** `verified=false`로
  "요약을 검증할 수 없음 — 콘솔에서 원문을 확인하세요"를 표시한다. 불완전한
  요약(예: destroy 누락)을 근거로 승인하는 경로를 봉쇄한다.
- **단, 본문이 아예 남지 않은 결손은 요약 이전에 PLAN task에서 막는다.** 본문
  조회 실패나 행 유실은 요약도 없고 콘솔에서 볼 원문도 없다는 뜻이라, 승인자가
  근거 없이 버튼만 보게 된다 — 게이트가 막으려던 상황이 게이트 안에서 생긴다.
  게이트가 근거로 읽을 PLAN task는(그 PLAN만) 완료 attempt의 finished job 수만큼
  본문 있는 행이 남지 않았으면 성공 대신 재시도 가능한 `PLAN_LOG_UNAVAILABLE`로
  닫고 Plan을 다시 돌린다 — plan은 인프라를 바꾸지 않아 재실행에 부작용이 없고,
  새 attempt가 새 로그를 만들어 결손이 실제로 복구된다. 이것이 ADR-016 관측
  계약("기록 실패는 판정을 바꾸지 않는다")의 유일한 예외이며, 게이트가 읽지 않는
  PLAN에는 적용하지 않는다.
- **재시도로 복구되지 않는 것은 실패로 묶지 않는다.** 절단(본문이 컬럼 상한
  초과)과 파싱 불일치(로그 포맷 표류)는 같은 plan을 다시 돌려도 같은 결과라,
  실패로 처리하면 terraform 버전이 한 번 바뀔 때 승인 게이트가 붙은 모든 실행이
  동시에 멈춘다. 그 둘은 위 규칙대로 `verified=false` 요약으로 승인자에게 넘긴다
  (읽을 원문은 있다).

추출 어휘는 add/change/destroy가 전부가 아니다 — terraform plan 텍스트의 상태
분류를 전부 다룬다: `will be created`(add) · `will be updated in-place`(change) ·
`will be destroyed`(destroy) · `must be replaced`(**replace** — 집계 라인에는
add+destroy로 분산되므로 별도 검출 필수) · `will be imported`(import, 집계 라인이
`Plan: N to import, …`로 변형) · `will no longer be managed`(forget — 1.7+
`removed` 블록) · `has moved to`(집계 미포함). 파싱 전 ANSI 컬러 코드를 스트립한다.
no-op(변경 없는 리소스)은 plan 텍스트에 인쇄되지 않으므로 "변경 없음 N건"은 셀 수
없다(한계 수용). 속성 단위 diff·no-op 카운트가 요구되면 executor에
`terraform show -json` 제공을 협의한다(업그레이드 경로 — 지금은 만들지 않는다).
표시 등급: replace는 destroy와 같은 위험 등급, forget은 경고 등급.

**요약에는 크기 상한이 있다(무제한 외부 문자열을 판정 트랜잭션 컬럼에 넣지
않는다).** `plan_summary`는 승인 행 INSERT와 같은 write-back 트랜잭션에서
저장되므로, 큰 plan의 무제한 주소 목록이 저장 실패 → 게이트 진입 롤백 → 반복
claim으로 이어질 수 있다(원장 R10 "judgment-tx column overflow" 패턴의 재발
경로). 규약: 주소 목록은 **위험 순(destroy·replace 우선) 상한 개수**까지만 담고,
잘렸으면 `addresses_truncated=true`와 `omitted_count`를 요약 JSON에 명시한다.
직렬화 결과는 컬럼 한도 내로 바운드한다(초과 시 목록을 더 줄여서라도 — 집계
수치와 truncated 표식은 항상 살아남는다). Slack 메시지도 같은 상한 목록을
소비하며 "외 N건 — 콘솔에서 전체 확인"으로 표시한다. 잘림은 fail-closed
(`verified=false`)가 아니다 — 집계 수치는 여전히 검증된 값이고, 목록만 축약임을
표식이 알린다.

**PII 계약(ADR-022 상속 + 이 payload의 allowlist).** Slack 승인 요청 메시지의
허용 필드는 **닫힌 목록**이다: `target_ref`(ADR-022의 opaque 규칙·`toTargetRef`
재사용), `type`, `cloud_provider`, `environment`, 요약 집계 수치, destroy/replace
리소스 주소 목록(아래 마스킹 규칙), `expires_at`, `detail_url`(ADR-022 링크 규칙 —
base + id로만 조립), 그리고 요청 맥락 3필드(§결정 4) — `requested_by`(BFF가
검증한 **내부 계정 표시 identity**라 허용한다: 승인 결과의 `approver_name` 표시와
같은 범주이고, 승인 판단에 "누가 요청했는가"가 필수 입력이다), `requested_at`,
`request_note`(유일한 자유 텍스트 — 상한 200자·경계 검증, 불변식 3의 예외). 속성 값(before/after), raw 연결 식별자, 예외 텍스트는 요약·
Slack 본문에 직렬화하지 않는다(MUST NOT). **리소스 주소는 "코드 식별자라 무해"라고
일반화하지 않는다** — `for_each` 인덱스 키에 hostname/DB명 같은 외부 유래 값이
들어올 수 있으므로, Slack 본문의 주소는 **인덱스 세그먼트를 마스킹**한다
(`aws_instance.web["db1.prod…"]` → `aws_instance.web[…]`). 전체 주소·원문은 권한
있는 콘솔에서만 본다. `approver` 표시는 Slack 자체의 사용자 정보(클릭한 본인
채널)라 무해하며, DB의 `approver_id/name`은 내부 감사 기록이다.

### 6. ChatOps는 Slack Socket Mode — 인바운드 0, 발송·표시 모두 상태에서 파생

private cluster 제약(맥락)을 Socket Mode가 정확히 해소한다: 앱이 Slack으로
**아웃바운드 WebSocket**을 열어 두면 버튼 클릭 payload가 그 소켓으로 내려온다.
공개 HTTP 엔드포인트(기본 Interactivity Request URL 방식)는 쓰지 않는다.
필요한 네트워크는 Cloud NAT 경유 아웃바운드 443(HTTPS/WSS)뿐이다.

- **구성**: Bolt for Java(`bolt-socket-mode`), App-Level Token(`connections:write`)
  + Bot Token(`chat:write`). 발송·수신·동기화를 `SlackApprovalGateway` 하나가
  담당한다.
- **Slack 메시지는 투영(projection)이다 — 진실은 `task_approval` 행이다.** 정합성은
  Slack 표시의 성공 여부에 의존하지 않는다: 표시가 늦거나 실패해 버튼이 살아있는
  낡은 메시지가 남아도, 그 클릭은 decide()의 CAS에서 0행으로 떨어져 무해하다.
  같은 이유로 **상태 전이 트랜잭션 안에서 Slack을 호출하지 않는다**(ADR-022가
  기각한 dual-write).
- **발송도 커밋된 상태에서 파생한다(디스패치 트랜잭션 밖).** 게이트 디스패치는
  승인 행(`REQUESTED`)과 `AWAIT_APPROVAL` 전이를 write-back 트랜잭션으로 먼저
  커밋한다 — **외부 호출 없음**. 발송은 커밋된 행에서 파생된다: 술어
  `status='REQUESTED' AND slack_message_ts IS NULL`(+ backoff 게이트
  `slack_sync_next_at IS NULL OR <= now`)인 행을 sweep이 처리한다.
- **점유는 ADR-022의 락-중-호출이 아니라 선점 스탬프 2-tx다.** `TerminalNotifier`
  헤더가 락-중-호출의 성립 조건을 명시한다 — "잠근 행을 노리는 다른 작업이
  없다". 승인 행에서는 이 조건이 **설계상 깨진다**: 같은 행을 decide()(승인
  클릭)와 워커의 만료 CAS가 노리므로, Slack이 느려진 10초짜리 호출 동안 행 락을
  쥐면 승인 API가 블록되고(BFF 타임아웃), pipeline 락을 쥔 워커의 write-back이
  승인 행 락 대기에 걸려 **Slack 지연이 판정 경로 전체를 정지시킨다**. 헤더가
  지시한 분리 설계를 그대로 채택한다: **짧은 점유 tx**(`FOR UPDATE SKIP
  LOCKED`로 집고 `slack_sync_next_at = now + lease` 스탬프 후 즉시 커밋) →
  **Slack 호출(트랜잭션 밖)** → **짧은 기록 tx**(`ts`/`synced_at` 기록, 발송
  성공 시 `slack_sync_next_at`도 null로 리셋 — 남겨두면 직후 결정의 `chat.update`가
  backoff 게이트에 최대 수 분 막힌다). 멀티 파드 동시 발송은 스탬프가 막고,
  "호출 성공 ~ 기록 tx 사이 크래시" 창의 중복 메시지는 ADR-022가 수용한 Slack
  중복과 같은 등급이다(사람이 상관; 낡은 중복 메시지의 버튼은 CAS가 흡수).
- **발송 메시지 구성** — 승인 판단에 필요한 맥락을 닫힌 필드로 담는다:
  대상(target ref)·작업 표시명(예: "AWS 서비스 테라폼 Apply"), **요청자
  (`requested_by`)**, **확인 요청 메시지(`request_note`, 있으면)**, **요청
  시각(`requested_at`)**, plan 요약(§결정 5 — fail-closed), 만료 시각, 콘솔
  상세 링크, 승인/반려 버튼. `request_note`는 이 payload에서 유일한 자유
  텍스트다 — 자동 추출물이 아니라 **사람(관리자/담당자)이 승인자에게 의도적으로
  쓰는 메시지**라 PII allowlist의 닫힌-필드 원칙과 범주가 다르며, 상한
  200자·경계 검증(§결정 4)을 조건으로 수용한다(불변식 3에 예외 명시).
  **렌더링 안전(MUST)**: `request_note`·`requested_by`는 Block Kit
  `plain_text`로만 렌더링한다(mrkdwn 금지) — `<!channel>` 멘션이나
  `<url|라벨>` 링크 문자열이 해석되면 채널 소란·승인 문맥 오인이 되므로,
  자유 텍스트가 Slack 제어 문법으로 실행되지 않음을 회귀 테스트로 고정한다.
  덮어써 버튼을 제거하고 결과("✅ ○○ 승인 · 시각" / "⛔ 반려됨" / "⏱ 만료됨" /
  "⏹ 취소됨")를 고정하고, 스레드 답글(`chat.postMessage` + `thread_ts`)로 전이
  이력을 남긴다. Phase 1 스레드 이력은 결정·만료·취소까지만 — APPLY 결과 연동은
  ADR-022 종단 알림과의 채널 정리와 함께 후속.
- **결과 표시는 derive-from-state로 동기화한다 — 보장은 give-up까지 조건부다.**
  술어 `status != 'REQUESTED' AND slack_message_ts IS NOT NULL AND slack_synced_at
  IS NULL`인 행을 sweep이 집어(점유는 위와 같은 선점 스탬프 2-tx) `chat.update` →
  스레드 답글 순으로 수행하고 둘 다 성공 시 `slack_synced_at`을 기록한다(실패 시
  attempts 증가 + 선형 backoff, 임계 도달 시 give-up — ADR-022 §2 축소 적용).
  `chat.update`는 같은 메시지 덮어쓰기라 멱등이고, 스레드 답글은 재시도 시
  **중복될 수 있다(at-least-once, 수용)** — 표시 이력의 중복은 사람이 상관하며,
  ADR-022의 Slack 중복 수용과 같은 등급이다.
- **give-up 등급은 술어별로 다르다 — 발송은 give-up하지 않는다.** 동기화 give-up의
  결과는 "낡은 메시지 잔존"이고 진실은 콘솔에서 항상 보이므로 ERROR 로그로
  충분하다(ADR-022식 경보 불요). 그러나 **발송 give-up의 결과는 승인 요청 유실**
  이다: 채널에 메시지가 끝내 안 뜨면 관리자는 요청 사실 자체를 모르고, 24시간 뒤
  만료 → 파이프라인 확정 FAILED → PLAN부터 재실행이다. 같은 규칙으로 묶을 수
  없다. 발송 술어는 attempts 임계에서 멈추지 않고 **만료 시각까지 상한
  backoff(횟수×1분, 상한 5분)로 계속 재시도한다** — `expires_at`이 자연 상한이라
  무한 재시도가 아니다. 미발송 상태가 임계(10분)를 넘으면 ADR-022의 give-up과
  동급으로 취급한다: 반복 ERROR 경보 + 폴러블 카운트(`countUnsentApprovalRequests`).
- **sweep은 전용 단일 스레드 loop다.** `TerminalNotifier`에의 편승은 기각한다 —
  그 loop는 `pipeline.notify.enabled=false`면 아예 시작하지 않으므로, 종단 알림은
  끄고 승인 Slack만 켜는 정상 조합에서 죽는다. 같은 패턴(단일 스레드, 부팅 gate,
  10초 주기)의 loop를 `pipeline.approval.slack-enabled` gate로 하나 더 둔다 —
  발송·동기화 두 술어를 이 loop가 함께 드레인한다.
- **ack은 durable commit 뒤에 한다.** 버튼 수신 시 `decide()`의 단일 짧은
  트랜잭션을 **먼저 커밋하고 나서 ack**한다(3초 제한 내 충분). ack을 먼저 하면
  직후 크래시 시 Slack은 수신 완료로 보고 DB에는 결정이 없어 클릭이 조용히
  유실된다. DB 장애 시에는 ack하지 않는다 — 사용자가 오류를 보고 재시도한다.
- **`response_url`은 재시도 창구로 쓰지 않는다(MUST NOT).** 발급 후 30분·5회
  제한의 일회성 URL이라 영속 재시도에 부적합하다 — 영속 갱신은 반드시
  `slack_message_ts` 기반 `chat.update`로 한다. `response_url`은 즉석 ephemeral
  피드백 전용이다: 이미 결정된 요청의 버튼을 누른 사용자에게 "이미 ○○ 님이
  승인했습니다"를 본인에게만 표시(유실 수용 — 채널 오염 방지용 편의).
- **멀티 파드**: 파드마다 소켓을 열지만 Slack은 이벤트를 하나의 연결로만 전달하며,
  설령 중복 수신해도 decide()의 CAS가 흡수한다.
- **기존 ADR-022 경로와 분리**: 종단 알림의 Incoming Webhook(`SlackNotifier`)은
  단방향이므로 그대로 두고 건드리지 않는다. 양방향(Socket Mode)은 별도 Slack 앱
  등록이 선행 조건이다(워크스페이스 관리자 협의).

### 7. 승인 권한 — Phase 1은 관리자만

- **콘솔**: approve/reject는 관리자 권한으로 한정하며, 강제 지점은 BFF다
  (§결정 4 신뢰 경계 — 이 ADR은 오케스트레이터에 새 인증 모델을 만들지 않는다).
- **Slack**: 관리자 전용 **비공개 채널** 멤버십이 곧 권한이다(채널 초대 = 승인
  권한 부여). 핸들러의 서버 측 검증(§결정 4)이 채널·팀·task 일치를 확인한다.
  Slack user id allowlist(env)는 Phase 1에서 만들지 않는다 — 설정 표면 최소화
  원칙. 채널 통제가 불충분해지는 시점(담당자 확대)에 도입을 재검토한다.
- 승인자 식별자(콘솔 계정 / Slack `user.id`)와 채널(`CONSOLE`/`SLACK`)은
  `task_approval`에 기록되어 감사 추적이 남는다.
- **Phase 2 방향(참고, 본 ADR 범위 밖)**: 서비스 담당자 승인은 **콘솔의
  TargetSource 상세페이지**에서 지원할 예정이며 인가는 BFF가 담당한다(BFF에
  인가 기능 기존재) — Slack 승인의 확장이 아니다(Slack은 관리자 채널 유지).
  오케스트레이터는 `decide()`·스키마 변경 없이 그대로다.

### 8. 설정 — env 주입, 기본 off, fail-fast

`pipeline.approval.*`(신규 `ApprovalSettings`, NotifySettings와 같은 fail-fast
컴팩트 생성자):

| 키 | 의미 |
|---|---|
| `enabled` | 기본 `false`. **신규 생성의 활성 레시피 버전 선택(§결정 1)만 지배한다** — `false`면 게이트 없는 V1만 생성된다. enum 값·게이트 TaskType 등록·claim 술어·`StepRunner` 분기·상태 전이는 플래그와 무관하게 **항상 활성**이다: 이들을 플래그로 게이팅하면 `true→false` 전환 시 진행 중 WITH_ADMIN_CONSENT 파이프라인의 `AWAIT_APPROVAL` 행이 영원히 미claim으로 고착된다(부팅 검증 `verifyEveryOperationResolves`가 TaskType 조건부 등록은 어차피 거부한다) |
| `timeout` | 승인 만료(기본 `PT24H`) |
| `slack-enabled` | 기본 `false`. `true`면 아래 토큰·채널이 전부 필수(누락 시 시작 실패) |
| `slack-app-token` / `slack-bot-token` | Socket Mode 연결·발송 토큰(secret — 로그 금지) |
| `slack-channel-id` | 관리자 채널(핸들러 검증 기준값 겸용) |

콘솔 상세 링크 base는 ADR-022의 `pipeline.notify.detail-url-base`를 재사용한다
(같은 콘솔 상세 화면 — 키를 늘리지 않는다). 단 **공유 키의 fail-fast는 소비자별로
건다**: `slack-enabled=true`면 notify 활성화 여부와 무관하게 `detail-url-base`가
명시 주입되어야 한다(기본값 localhost로 프로덕션 승인 메시지가 나가는 사고 방지).
검증 지점은 `ApprovalSettings` 자체가 아니다 — 그 record는 `pipeline.approval.*`
에만 바인딩되므로 다른 prefix를 볼 수 없다. **두 설정(`ApprovalSettings`·
`NotifySettings`)을 함께 주입받는 부팅 검증 빈**이 교차 조건("approval Slack on
⇒ detail-url-base 명시 주입")을 확인하고 위반 시 시작 실패시킨다. Slack team
기준값도 부팅 시 `auth.test` 1회로 확정한다(§결정 4 — 설정 키 아님).

### 9. DELETE(destroy) 게이트 — plan을 신설해 게이트를 첫 파괴 행위 앞에 둔다

기존 DELETE 레시피는 **plan 없이** destroy만 설치 역순으로 수행한다(BDC →
서비스; GCP는 서버 강제 순서). 여기에 게이트를 그대로 얹을 수 없는 이유가 둘이다:
승인을 기존 순서의 중간(BDC destroy 뒤)에 두면 **승인 시점에 이미 부분 파괴가
끝나** 있어 승인이 무의미하고, 순서 맨 앞에 승인만 두면 **보여줄 plan이 없어**
맹목 승인이 된다.

**결정(오너 지시 2026-08-22)**: destroy 게이트 레시피는 **service destroy plan을
신설**해 다음 순서로 구성한다 —

```
AWS_DELETE_WITH_ADMIN_CONSENT_V1:
  service destroy plan → [승인 게이트] → BDC service level destroy
                        → BDC common destroy → service destroy
GCP_DELETE_WITH_ADMIN_CONSENT_V1:
  service destroy plan → [승인 게이트] → BDC destroy → service destroy
```

게이트 뒤의 destroy 순서는 기존 DELETE 레시피와 동일하다(서버 강제·역순 규약
유지). 범위는 Phase 1 스코프와 같은 축인 AWS/GCP 2종이다(Azure/IDC 후속).

- **신규 operation — destroy plan. 이름은 `_TF_PLAN` suffix를 보존한다:
  `AWS_SERVICE_DESTROY_TF_PLAN`/`GCP_SERVICE_DESTROY_TF_PLAN`**(terraform plan
  -destroy, mechanism=TERRAFORM_JOB). suffix가 계약을 셋이나 지배하므로 여기서
  확정한다(구현으로 미루지 않는다): ① `terraformAction()`은 마지막 `_TF_` 뒤를
  라벨로 반환하므로 `..._TF_DESTROY_PLAN`이면 허용 밖 라벨 `DESTROY_PLAN`이
  와이어·테스트 계약(PLAN/APPLY/DESTROY)을 깬다 — `_TF_PLAN` 보존으로 라벨은
  **PLAN**. ② job type 바인딩은 **`TerraformJobType.PLAN`**이다(destroy 모드
  plan도 job으로서는 plan): 성공 판정 List가 `CREATED/COMPLETED/COMPLETE`인데,
  이름의 DESTROY를 따라 DESTROY로 바인딩하면 `CREATED`가 "진행 중"으로 남아
  execution timeout까지 간다. ③ destroy 의미 구분은 라벨이 아니라
  **`TaskDefinition` 표시명**("… 테라폼 Destroy Plan")이 담당한다.
  **InfraManager가 destroy 모드 plan을 지원하는지 API 협의가 선행 조건**이다
  (이 의존 때문에 구현을 PR 4로 분리).
- **게이트 op는 destroy용을 별도로 둔다 — `TF_DESTROY_APPROVAL`.**
  mechanism=APPROVAL, 같은 `ApprovalGateTask`를 타므로 실행 로직은 공유하고 enum
  값과 provider별 `TaskDefinition` 2개(§결정 1과 같은 이유)만 는다. "무엇에
  동의하는가"가 apply와 destroy에서 다르므로 **표시**가 정직해진다(표시명 규약:
  실행 단위 + 테라폼 + Destroy). 통계는 구분하지 않는다 —
  `await_approval_count`는 상태 수준 집계라 apply/destroy 승인 대기를 나누지
  않으며, operation별 집계 요구가 생기면 별도 계약으로 후속한다.
- **승인 커버리지의 한계를 요약에 명시한다(MUST).** 승인자가 보는 plan 요약은
  **service 수준 destroy만** 커버한다 — 게이트 뒤에 실행되는 BDC destroy는
  요약에 없다. Slack·콘솔 요약에 "BDC 인프라 destroy가 함께 실행됩니다(이
  요약에 미포함)" 고정 문구를 넣는다. BDC destroy plan까지 게이트 앞에 배열하는
  대안은 기각한다 — step이 배로 늘고 BDC는 서비스의 부속 단위다. 커버리지
  요구가 생기면 그때 확장한다.
- plan 시점과 실행 시점 사이의 드리프트는 수용한다 — destroy는 대상 전체
  제거라 install보다 드리프트 민감도가 낮고, 승인 만료(기본 24h)가 노출 창의
  상한이다.

## 고려한 대안

| 대안 | 판정 | 이유 |
|---|---|---|
| **A. 승인 게이트 task + `AWAIT_APPROVAL` + `next_due_at` 대기** | **채택** | 레시피가 이미 PLAN→APPLY를 별도 task로 배열하고 `NETWORK_READY` 선례가 있어, 기존 상태 머신·claim·감사 이력을 재사용. PENDING 선례(claim 시 RUNNING flip)가 재개 방향을 흡수(진입 방향은 `StepReporter` 분기 필요 — §결정 2). |
| B. PLAN task에 승인 상태 부가 | 기각 | "PLAN 성공인데 task 미완료" 모순 상태; PLAN 재시도와 승인 로직이 한 task에 얽힘(§결정 1). |
| C. 파이프라인 일시정지 플래그(`paused_until` 류) | 기각 | 어느 단계 사이에서 멈췄는지 모델에 없어 UI·감사가 파생 로직 투성이가 됨. |
| D. ChatOps를 HTTP Interactivity(공개 Request URL)로 | 기각 | private cluster에 인바운드 경로가 없음 — 공개 ingress/터널 신설은 승인 기능 하나를 위한 보안 표면으로 과하다. Socket Mode가 아웃바운드만으로 동일 기능 제공. |
| E. 승인을 콘솔 전용으로(ChatOps 없음) | 부분 채택 | PR 1은 실제로 콘솔 단독으로 완결된다(§롤아웃). Slack은 그 위의 additive 채널 — 다만 "관리자가 콘솔을 열지 않아도 처리"가 본 요구의 핵심이라 Phase 1 범위에 포함한다. |
| F. plan 요약을 `terraform show -json` 기반으로 | 유보(업그레이드 경로) | 구조화 diff·no-op 카운트까지 얻지만 executor API 협의·전송 경로 신설이 필요. 표시 전용 + fail-closed 규칙(§결정 5) 하에서는 저장된 로그 파싱으로 충분. |
| G. 별도 승인 서비스/워크플로 엔진 | 기각 | 규모(내부 도구)가 정당화하지 않음. 승인은 task 하나의 생애일 뿐 — ADR-016 개념 최소화. |
| H. Slack 동기화를 `TerminalNotifier`에 편승 | 기각(codex 1R) | 그 loop는 notify gate로 기동되므로 notify off + 승인 Slack on 조합에서 죽는다. 전용 loop 1개 추가가 정직한 비용(§결정 6). |
| I. Phase 1 스레드 이력 제거(원 메시지 갱신만) | 기각(오너 요구 유지) | 마커 하나로 두 부작용을 exactly-once 보장할 수 없다는 지적은 타당하나, `chat.update` 멱등 + 스레드 at-least-once(중복 수용 — ADR-022 선례)로 보장 수준을 정직하게 낮춰 유지한다(§결정 6). |
| J. Slack 요약을 집계 수치 + 콘솔 링크로 축소(리소스 목록·마스킹·절단 규약 삭제) | 기각(오너 요구 유지, 6차 리뷰 제안) | plan 원문은 tf 문법 숙련자만 읽을 수 있어 자연어 요약이 게이트의 핵심 가치다 — 축소하면 "링크 열어 원문 읽기"로 회귀한다. 표시의 세 위험(컬럼 오버플로·PII·오파싱 승인)은 §결정 5의 상한·마스킹·fail-closed가 각각 방어하며, 그 구현 비용을 지불한다. |

## 롤아웃

1. **PR 1 — 백엔드 코어**: enum·`task_approval`·게이트 task·상태 머신 전이·
   §결정 2 파급 목록 전부·approve/reject API·요약 추출기·생성 API의
   `requested_by`/`request_note` 수용(§결정 4 요청 맥락). **콘솔만으로
   end-to-end 동작**(Slack 없이).
2. **PR 2 — 콘솔 UI**: 상태 표시(`승인 대기`)·승인 모달(요청자·요청 메시지·요청
   시각·승인자 **read-only 표시**)·BFF proxy route + 관리자 권한 강제·identity
   주입(승인자와 요청자 양쪽). **`request_note` 입력은 파이프라인 생성 UI다** —
   최초 Slack 발송은 게이트 진입 커밋 직후 파생되므로(§결정 6), 승인 모달에서
   입력하면 발송 시점에 항상 누락된다. 생성 시점에 이미 담겨 있어야 한다. (콘솔 repo `pii-agent-demo` — 타입 추가 시 exhaustive
   map들이 컴파일 에러로 수정 지점을 전부 지목한다.)
3. **PR 3 — ChatOps**: `SlackApprovalGateway`(발송·수신·동기화 sweep) + Slack 앱
   등록(Socket Mode). `slack-enabled=true`로 켠다.
4. **PR 4 — DELETE(destroy) 게이트(§결정 9)**: destroy plan operation 신설 +
   `TF_DESTROY_APPROVAL` + `*_DELETE_WITH_ADMIN_CONSENT_V1` 레시피 +
   **`RecipeDefinitionTest`의 "DELETE는 destroy step만" 회귀 규약 갱신**(gated
   DELETE는 plan·게이트를 포함하므로 그대로 두면 실패 — gated 레시피의 정확한
   step 순서를 별도 테스트로 강제).
   **선행 조건: InfraManager destroy plan API 협의** — 협의 결과가 나올 때까지
   PR 1~3과 독립적으로 보류 가능(게이트 공통 기반은 PR 1이 완성).

기존 파이프라인·V1 레시피는 어느 PR에서도 영향받지 않는다. `enabled=false`
기본값이므로 배포 자체는 무해하고, 켜는 시점을 운영이 정한다.

## 결과

### 좋은 점

- **되돌리기 비싼 변경(destroy/replace) 앞에 사람 확인 지점**이 생기되, 기존
  실행 모델의 재사용(PENDING flip 선례 포함)으로 새 메커니즘이 최소다.
- **승인 대기는 자원을 점유하지 않는다** — `next_due_at`이 미래라 claim 자체가
  안 되고, `RUNNING`이 아니라 실행 캡·terraform slot도 소진하지 않는다.
- **채널 무관 단일 결정 경로** — 콘솔/Slack이 같은 `decide()` CAS로 수렴해 권한·
  멱등·감사 규칙이 한 곳이고, 승인·만료·취소의 race는 전부 같은 행의 CAS로
  원자 판정된다.
- **private cluster 제약 유지** — 인바운드 표면을 열지 않는다(아웃바운드 443만).

### 수용하는 비용

- **파이프라인 소요 시간에 사람 대기가 들어온다.** 만료(기본 24h)로 상한을 둔다.
- **enum 2곳 확장의 파급.** §결정 2의 수정 지점 목록이 그 실체다 — 콘솔 exhaustive
  map 갱신 포함(컴파일 타임에 드러나는 설계된 안전망 — 침묵 회귀 없음).
- **plan 로그 텍스트 포맷 의존.** terraform 메이저 업그레이드 시 파서 회귀
  테스트(고정 로그 fixture)로 방어하고, 파싱 정합이 깨지면 fail-closed로
  `verified=false` 표시가 된다(잘못된 요약 노출은 없음). 포맷 표류가 잦아지면
  대안 F로 전환한다.
- **Socket Mode 상시 연결 의존.** 연결 단절 중 버튼 클릭은 Slack이 재전달하지
  않을 수 있다 — 승인은 콘솔로 항상 가능하고, 게이트 자체는 DB 상태라 유실이
  없으므로 수용(재연결은 SDK가 처리).
- **Slack 메시지는 at-least-once.** 최초 발송(ts 기록 전 크래시)과 스레드
  답글(동기화 재시도)에 중복이 가능하다 — ADR-022의 Slack 중복 수용과 같은
  등급으로 수용(사람이 상관, 버튼은 CAS가 흡수). "유실 없음"의 실체는 **give-up
  임계까지의 재시도**다(조건부 보장 — give-up 후엔 콘솔이 진실).
- **단일 스레드 loop 1개 추가**(`slack-enabled` gate) — TerminalNotifier 편승
  불가의 정직한 비용.
- **관리자 병목.** Phase 1은 승인 주체가 관리자뿐이라 부재 시 만료가 날 수 있다 —
  담당자 확대(Phase 2)의 동기로 수용.

## 스키마

신규 테이블 1개 — 게이트 task와 1:1. 기존 컬럼의 **변경**은 없고(상태값 이름이
`VARCHAR(16)`에 맞춰져 있다 — §결정 2), `pipeline`에 **추가** 컬럼 2개만 는다
(additive — hbm2ddl update가 안전하게 만든다):

```
pipeline (추가 컬럼 — §결정 4 요청 맥락)
  requested_by   VARCHAR(64)  NULL   -- 생성자 identity(BFF 주입, write-once). 게이트 레시피 생성 시 필수(400)
  request_note   VARCHAR(200) NULL   -- 확인 요청 메시지(요청자 자유 텍스트, 초과는 typed 400 거절 — 절단 없음, §결정 4)
```

```
task_approval
  id                BIGINT PK
  task_id           BIGINT NOT NULL UNIQUE     -- 게이트 task와 1:1
  status            VARCHAR(16) NOT NULL       -- REQUESTED / APPROVED / REJECTED / EXPIRED / CANCELLED
  requested_at      TIMESTAMP NOT NULL
  expires_at        TIMESTAMP NOT NULL         -- requested_at + timeout (디스패치 시 고정)
  decided_at        TIMESTAMP NULL
  approver_id       VARCHAR(64) NULL           -- Slack user id 또는 콘솔 계정(BFF 주입)
  approver_name     VARCHAR(64) NULL
  channel           VARCHAR(16) NULL           -- CONSOLE / SLACK
  slack_message_ts  VARCHAR(32) NULL           -- 발송 완료 마커 겸 chat.update 대상
  plan_summary      TEXT NULL                  -- §결정 5의 요약 JSON 캐시(verified 플래그 포함)
  -- Slack 표시 동기화 메타데이터(§결정 6 derive-from-state) — ADR-022의
  -- notified_at/notify_next_at/notify_attempts와 같은 범주, 도메인 상태 아님
  slack_synced_at    TIMESTAMP NULL            -- 결정/만료/취소가 원 메시지에 반영 완료된 마커
  slack_sync_next_at TIMESTAMP NULL            -- backoff 게이트 겸 선점 스탬프 lease(§결정 6 2-tx) — 발송 성공 시 NULL 리셋
  slack_sync_attempts INT NOT NULL DEFAULT 0   -- 동기화 give-up 임계 판정용(발송 술어는 give-up 없음 — §결정 6; 발송 완료 시 0으로 리셋)

  INDEX idx_task_approval_slack (status, slack_synced_at, slack_sync_next_at)
  -- sweep 두 술어의 커버링 — FOR UPDATE 풀스캔은 스캔한 행 전부에 락을 걸어
  -- decide()·만료 CAS와의 경합을 전 행으로 확대하므로 인덱스가 필수다
  -- (Pipeline의 idx_pipeline_notify 선례, 원장 index-coverage 패턴)
```

`TaskSummary`/`TaskDetail` 응답에 `approval` 블록(status, requested_at, expires_at,
decided_at, approver, channel, plan_summary)을 추가하고, **`PipelineDetail`에만** `requested_by`/`request_note`를 노출한다
(승인 화면의 최소 계약 — 생성·상세·취소 응답). 목록용 `PipelineSummary`에는
넣지 않는다 — 자유 텍스트를 모든 목록 응답으로 확산시키지 않는다.

**불변식**

1. **게이트의 전이 판정 입력은 `task_approval.status`와 `expires_at`뿐이다.**
   `terraform_result`·요약은 표시 payload 전용이며(§결정 5 예외), 전이 로직은
   읽지 않는다. 상태 전이는 워커의 write-back 경로만 쓴다(§결정 2의 소유
   컴포넌트 구분) — `decide()`는 결정
   기록·웨이크업·(반려 시) `cancel_requested` 설정뿐, task/pipeline **상태**를
   직접 쓰지 않는다(`cancel_requested`는 기존 취소 API도 세우는 요청 플래그이지
   상태 전이가 아니다).
2. **승인 행의 결정 전이는 전부 `REQUESTED`에서 출발하는 CAS다**(승인/반려는
   `expires_at > now` 추가 조건). `APPROVED`/`REJECTED`/`EXPIRED`/`CANCELLED`는
   종단이며 되돌리지 않는다 — 재요청은 새 파이프라인 재실행(§결정 3)이 새 task·
   새 승인 행을 만든다(기록 불변, 감사 보존).
3. 승인 요청·요약(payload)은 ADR-022의 PII 계약 + §결정 5의 allowlist를 따른다 —
   집계·마스킹된 주소·닫힌 필드만, 속성 값·raw 연결 식별자·예외 텍스트 금지
   (MUST NOT). 유일한 자유 텍스트 예외는 `request_note`(§결정 4·6 — 사람이
   승인자에게 의도적으로 쓰는 메시지, 상한 200자·경계 검증)다.
4. 게이트 task는 terraform slot을 소비하지 않으며, **승인 대기 중에는** 실행
   캡을 점유하지 않는다(§결정 2 — cap은 활성 claim 수 기준이고 대기 중엔 claim이
   없다; 진입·결정 반영·만료 처리의 짧은 claim 순간은 일반 task와 동일하게
   계상된다).
5. **Slack 표시는 투영이다** — 승인 정합성(결정 기록·상태 전이·만료 판정)은
   `chat.update`/스레드/ephemeral의 성공 여부에 의존하지 않는다.
   `slack_message_ts`·`slack_synced_at` 등 동기화 컬럼은 표시 메타데이터이며
   도메인 전이 로직은 읽지 않는다.
6. **승인 행을 만지는 모든 트랜잭션의 락 순서는 pipeline → task_approval이다**
   (§결정 4). 워커 write-back·decide()·취소 경로 전부 — 순서가 갈리면 만료
   시각 부근에서 ABBA 데드락이 된다. Slack 호출은 어느 행 락 아래에서도 하지
   않는다(§결정 6의 선점 스탬프 2-tx).

## 용어

- **승인 게이트(approval gate)** — plan 결과 확인과 첫 실제 변경 사이에 삽입되는
  task(install 레시피는 PLAN과 APPLY 사이, DELETE 레시피는 destroy plan과 첫
  destroy 사이 — §결정 9). 사람의 결정(승인/반려) 또는 만료·취소가 있어야
  종결된다.
- **ChatOps** — 채팅 도구(Slack)에서 운영 행위(여기서는 승인/반려)를 수행하는
  방식. 본 ADR에서는 Socket Mode 기반 양방향 상호작용을 뜻한다.
- **Socket Mode** — Slack 앱이 공개 HTTP 엔드포인트 대신 아웃바운드 WebSocket으로
  이벤트·인터랙션 payload를 받는 연결 방식. private 네트워크의 내부 앱을 위한
  공식 경로.
- **replace** — terraform이 in-place 변경 불가로 판단해 destroy 후 create하는
  변경(`must be replaced`). 집계 라인에는 add+destroy로 분산되므로 별도 검출한다.
- **forget** — `removed` 블록(terraform 1.7+)에 의해 리소스를 파괴하지 않고
  state 관리에서만 제외하는 변경.
- **fail-closed 요약** — 원천 데이터(모든 job의 result 존재·파싱 정합·비절단)가
  완전할 때만 수치를 표시하고, 아니면 수치 대신 "검증 불가"를 표시하는 규칙.
  불완전 요약을 근거로 한 승인을 봉쇄한다.

## 개정 이력

- 2026-08-22: 생성. 오너 결정 반영 — Phase 1 승인 주체는 관리자만, 게이트 대상은
  AWS/GCP SERVICE 단위 APPLY 2종(`AWS_SERVICE_TF_APPLY`/`GCP_SERVICE_TF_APPLY`),
  반려는 CANCELLED·만료는 FAILED(APPROVAL_EXPIRED)·기본 만료 24h는 제안값으로
  포함(확정 시 본문 유지, 변경 시 이 절에 기록).
- 2026-08-22 (2차): **Slack 상태 표시의 유실·중복 처리 확정(오너 승인).**
  투영 원칙·dual-write 금지 명문화, derive-from-state 동기화 마커, give-up 경보
  차등, `response_url` 영속 재시도 금지, 스레드 이력 Phase 1 범위 확정.
- 2026-08-22 (3차): **codex 1라운드 리뷰 반영(gpt-5.6-sol xhigh — P0 3·P1 9·P2 1,
  merge-ready no).** 실코드 대조 기반 지적을 다음과 같이 수용/판정:
  - **P0**: 승인 CAS에 `expires_at > now` 조건 추가 + 만료를 워커의
    `REQUESTED→EXPIRED` CAS로 원자 판정(0행이면 결정 재독) — §결정 3·4. 상태값을
    `AWAITING_APPROVAL`(17자)→**`AWAIT_APPROVAL`**(14자)로 변경(status 컬럼
    `VARCHAR(16)`, hbm2ddl update는 길이 변경 불가). V2 레시피 병존→**활성 버전
    선택**(카탈로그 (provider,type)당 1개 유지, `approval.enabled`가 선택,
    비활성 버전은 이름 해석 전용) — §결정 1.
  - **P1**: `RUNNING`/`PENDING` 하드코딩 경로의 파급을 표로 완전 명세(PENDING
    flip 선례 채택 — claim이 RUNNING으로 flip, 취소 Case A/B에 AWAIT_APPROVAL
    포함 + 승인 행 CANCELLED CAS) — §결정 2. `terraform_result` 읽기를 ADR-016
    예외로 명시 선언하되 표시 전용 + **fail-closed 요약**(전 job 존재·파싱 정합·
    비절단일 때만 수치 표시) — §결정 5. 발송을 write-back 커밋 후
    derive-from-state로 이동(전이표의 "발송 성공" 조건 제거 — 문서 모순 해소,
    최초 발송도 재시도 대상) — §결정 6. TerminalNotifier 편승 기각→전용 loop
    1개(notify off 조합 결함) — §결정 6·대안 H. ack을 decide() 커밋 후로 —
    §결정 6. "restart 재사용" 표현 제거→새 파이프라인 재실행으로 정정(종단 부활
    금지 준수) — §결정 3·불변식 2. 신뢰 경계 명시(BFF identity 주입 계약 +
    Slack 핸들러 서버 측 검증) — §결정 4. PII: 리소스 주소 인덱스 마스킹 +
    payload allowlist 명시, 공유 `detail-url-base`의 소비자별 fail-fast — §결정
    5·8.
  - **P2/일부 P1 기각**: 스레드 이력 제거 권고는 오너 요구 유지로 기각 —
    `chat.update` 멱등 + 스레드 at-least-once(중복 수용, ADR-022 선례)로 보장
    수준을 정직화(대안 I). custom recipe의 승인 operation 배치는 생성 검증에서
    명시 거부(Phase 1 범위 절).
- 2026-08-22 (4차): **codex 2라운드 반영(P0 0·P1 7·P2 2 — 1라운드 P0 전부 해소
  확인).** 잔존 P1/P2를 다음과 같이 정리:
  - 실행 계약 파급 추가(§결정 2) — `ApprovalGateTask`의 `TaskTypeRegistry` 등록,
    `StepRunner`의 READY/AWAIT_APPROVAL 분기, sealed 결과(`TaskProgress`/
    `StepOutcome`)의 게이트 진입 변형 1개(`AwaitApproval`), 기간 통계 합산 포함.
  - 최초 발송에도 결과 동기화와 동일한 점유(`FOR UPDATE SKIP LOCKED` 행 잠금
    단일 tx + backoff/give-up) 명시 — 멀티 파드 동시 발송 창 제거(§결정 6).
  - `decide()` 0행 의미 이원화 — "이미 결정됨" vs "기한 경과·만료 처리
    대기"(REQUESTED인데 expires_at 경과)를 별도 결과로 구분(§결정 4).
  - Slack team 검증 기준값 = 부팅 시 `auth.test` 1회 확정(설정 키 미추가, §결정
    4·8). 공유 `detail-url-base` fail-fast는 두 설정을 함께 받는 부팅 검증
    빈으로 명시(§결정 8 — record 자체 검증 불가 지적 수용).
  - `origin_pipeline_id` 이력 연결 보장 삭제 — 현 스키마에 없는 컬럼을 전제한
    문장이었음. 재실행은 "같은 target에 새 파이프라인"으로만 정의(§결정 3).
  - `plan_summary` 크기 상한 규약(§결정 5) — 위험 순 상한 목록 +
    `addresses_truncated`/`omitted_count`, 컬럼 한도 바운드(원장 R10 judgment-tx
    overflow 패턴 재발 방지). Slack도 같은 상한 목록 소비.
  - catalog에서 승인 정의 제외(Phase 1 범위 절), admission cap 근거를 "활성
    claim 수 기준"으로 정정(§결정 2·불변식 4).
- 2026-08-22 (5차): **codex 3라운드 반영(P0 0·P1 3·P2 3 — 2라운드 P1은 실행 계약
  1건 제외 전부 해소 확인). 게이트의 sealed 실행 계약을 실코드 반환형에 맞춰
  확정:**
  - 게이트 진입 변형은 `TaskProgress`가 아니라 **`DispatchResult`에** 추가
    (`AwaitApproval(expiresAt, planSummaryJson)`) — `execute()`의 실제 반환형이
    `DispatchResult`이고 `TaskProgress`는 `check()` 계약이므로. write-back이
    승인 행 INSERT·`AWAIT_APPROVAL` 전이·`next_due_at`·attempt 시작을 한
    트랜잭션으로 기록(§결정 2).
  - **반려의 값 경로 확정** — 새 outcome 변형 대신 기존 `cancel_requested` 취소
    경로 재사용: `decide(REJECT)`가 CAS와 함께 `cancel_requested=true` 설정,
    워커의 기존 취소 처리가 전체 CANCELLED 수행(§결정 2·4, 전이표·불변식 1
    정합화 — `cancel_requested`는 요청 플래그이지 상태 전이가 아님).
  - 기간 통계 wire 계약 확정 — `PipelineStatistics`에 `await_approval_count`
    필드 추가(PR 1), "상태별 합 = total" 구조 유지(§결정 2 파급표).
  - P2: 파급표에 `SlackNotifier` exhaustive switch 추가, 불변식 4를 "승인 대기
    중에는 캡 미점유"로 좁힘(진입·판정 순간의 짧은 claim은 일반 계상), 문서
    머리 개정 차수 정정.
- 2026-08-22 (6차): **독립 이중 리뷰 반영(Opus 설계 리뷰 P0 1·P1 6·P2 6 + 코드
  사실 전수 대조 78건 중 MISMATCH 4건 — 전부 실코드로 재검증 후 반영):**
  - **P0 — 게이트 진입 시 `next_due_at` 미전진.** `releaseClaim`의 `RUNNING`
    전용 가드 때문에 진입 write-back이 `next_due_at`을 안 쓰면 과거 값이 남아
    매 sweep 재claim된다(시간 가드 없던 만료 CAS와 결합 시 진입 직후 전원
    `APPROVAL_EXPIRED`). PENDING 선례를 "재개 방향 한정"으로 정정하고
    `StepReporter` 게이트 진입 분기(`next_due_at = expires_at` 명시 대입)를
    파급표에 추가(§결정 2).
  - 만료 CAS에 `expires_at <= :now` 시간 가드 추가 — decide 조건의 정확한
    여집합으로. 파드 로컬 시계 편차에서 빠른 파드가 유효 창을 조기 폐쇄하는
    경로 차단, 이른 웨이크업은 "대기 유지 + `next_due_at` 재설정"(§결정 3·4).
  - **판정의 seam 확정 — ADR-021 예외 선언.** run 단계 check로는 CAS 원자성이
    성립하지 않으므로 게이트는 `StepOutcome.ApprovalPoll` 변형으로 판정을
    write-back 트랜잭션에 위임한다(§결정 2).
  - **락 순서 고정(불변식 6)** — decide()도 pipeline 행 선잠금(pipeline →
    task_approval). 역순이면 만료 부근 ABBA 데드락.
  - **Slack 점유를 락-중-호출에서 선점 스탬프 2-tx로 교체(§결정 6)** — 승인
    행은 decide()·만료 CAS가 노리는 경합 행이라 `TerminalNotifier` 헤더의
    성립 조건이 깨진다(헤더가 지시한 분리 설계 채택). 발송 성공 시
    `slack_sync_next_at` 리셋 명시.
  - **give-up 등급 분리(§결정 6)** — 발송 give-up=승인 요청 유실(확정 만료
    실패)이므로 만료까지 상한 backoff 재시도 + 반복 경보 + 폴러블 카운트;
    동기화 give-up만 ERROR 로그로 완화 유지.
  - 코드 사실 정정: `TF_APPLY_APPROVAL`은 단일 operation이되 **provider별
    `TaskDefinition` 2개** 필요(`RecipeCatalog`의 provider 일치 부팅 검증;
    `NETWORK_READY` "여러 레시피 재사용" 인용은 허구라 삭제),
    `RecipeCatalog` 생성자 수정(설정 주입 + 비활성 버전 필터)을 명시,
    "TaskStateMachine 단일 경로" 서술을 실제 소유 컴포넌트 구분(task/pipeline)
    으로 정정, `markInProgress` instanceof·`TaskExecutionSpec` 팩토리 파급 추가.
  - P2: approver 문자열 경계 절단(R10 재발 방지), `task_approval` sweep 인덱스,
    `enabled` 플래그의 지배 범위 명문화(in-flight 행 상시 활성), 개정 차수 정정.
- 2026-08-22 (7차): **오너 지시 반영 — 레시피 명명과 DELETE 게이트:**
  - 게이트 레시피 명명을 `*_V2`에서 **`*_WITH_ADMIN_CONSENT_V1`**으로 교체(§결정
    1) — 게이트 유무는 병렬 변형이지 다음 버전이 아니므로 의미를 이름에 싣고,
    `_V2`는 내용 진화용으로 보존. 축 이름은 `PipelineType`(INSTALL/DELETE) 정합.
  - **§결정 9 신설 — DELETE(destroy) 게이트.** 기존 "BDC → 서비스 destroy"
    순서를 "**service destroy plan → 승인 → BDC destroy → service destroy**"로
    재구성(승인이 첫 파괴 행위에 선행해야 의미가 성립). destroy plan
    operation(`*_TF_DESTROY_PLAN`)·`TF_DESTROY_APPROVAL` 신설, 요약의 BDC
    미커버 명시 문구(MUST), 롤아웃 PR 4 분리(InfraManager destroy plan API
    협의 선행). Phase 1 범위 절의 "DESTROY 제외"를 "설계 확정·구현 후속"으로
    갱신. Phase 2 방향(콘솔 TargetSource 상세페이지의 담당자 승인, BFF 인가)
    §결정 7에 참고 기재.
- 2026-08-22 (8차): **요청 맥락 추가(오너 요구 — 누가·왜·언제 요청, 누가 승인).**
  승인자·요청 시각·API 승인은 기존 설계에 이미 있었고, **요청자 identity와 확인
  요청 메시지가 부재**였다(파이프라인에 `created_at`뿐, 생성 API는 target+type만
  수용 — 코드 확인). 생성 API에 `requested_by`(BFF 주입, 게이트 레시피 필수) +
  `request_note`(자유 텍스트, 상한 200자 경계 검증 — R10 규칙) 추가,
  `pipeline`에 additive 컬럼 2개, 발송 메시지 구성 명세(§결정 6), PII 불변식에
  note 단일 예외 명시, 콘솔 응답·롤아웃 PR 1/2 갱신. `task_approval` 복사
  없이 pipeline 행 단일 진실원(조인 표시).
- 2026-08-22 (9차): **7·8차분 codex 표적 리뷰 반영(P0 0·P1 3·P2 4):**
  - P1: destroy plan op 이름을 `*_SERVICE_DESTROY_TF_PLAN`으로 확정 — `_TF_PLAN`
    suffix 보존으로 `terraformAction()` 라벨(PLAN)·`TerraformJobType.PLAN`
    바인딩·성공 판정 List(CREATED/COMPLETED)가 기존 규약에서 그대로 파생된다
    (`_TF_DESTROY_PLAN`이면 라벨 계약 파괴 + DESTROY 바인딩 시 CREATED가 진행
    중으로 남아 execution timeout). "구현에서 확정" 유예 삭제(§결정 9).
  - P1: §결정 5 PII allowlist에 요청 맥락 3필드(`requested_by`/`requested_at`/
    `request_note`) 명시 추가 — §결정 6 메시지 구성과의 MUST 충돌 해소.
  - P1: `request_note` 입력 표면을 승인 모달에서 **생성 UI**로 정정(최초 발송이
    게이트 진입 직후라 모달 입력은 항상 누락) — 모달은 read-only 표시(롤아웃
    PR 2).
  - P2: 경계 계약을 typed 400으로 통일(`requested_by` non-blank·≤64,
    `request_note` ≤200, `OrchestrationErrorCode` 신규 값, 수용 DTO 두 갈래
    명시), 노출은 `PipelineDetail` 한정(`PipelineSummary` 제외), 전이표·용어
    절의 게이트 정의를 DELETE 포함으로 일반화, PR 4에 `RecipeDefinitionTest`
    회귀 규약 갱신 명시, `TF_DESTROY_APPROVAL`의 "통계 구분" 주장 삭제(상태
    수준 집계라 구분 불가 — 필요 시 후속 계약).
  - 검증 라운드(2R): P0 0·P1 0 — 1R 지적 전건 해소 확인. 잔여 P2 2건 반영:
    스키마 주석의 "절단" 잔재를 typed 400(무절단)으로 정정,
    `request_note`/`requested_by`의 Slack `plain_text` 렌더링 계약(MUST) +
    회귀 테스트 명시.
- 2026-08-22 (10차): **base 갱신 반영 — 파이프라인 재시작(#42)이 main에 병합됨.**
  1라운드에서 "restart API·origin 컬럼이 존재하지 않는다"를 근거로 썼던 §결정 3
  문단이 사실과 어긋나게 됐다(현재 main: `PipelineRestarter`,
  `POST …/restart`·`restart-preview`, `origin_pipeline_id`/`origin_task_id`).
  만료 재요청을 **기존 restart 경로 재사용**으로 정정하고(전용 API 불필요, 계보
  자동 기록), 그 과정에서 드러난 두 구멍을 PR 1 작업으로 명시: ① 기본 재시작
  지점이 만료된 게이트 자신이라 새 체인에 PLAN task가 없어 요약 원천
  (`terraform_result`)이 사라진다 → 지점을 PLAN까지 당겨야 하며 "게이트만
  재시작"은 불허(`fromSequence`가 앞으로만 허용되므로 가능), ② `restartOf`가
  승계하지 않는 `requested_by`/`request_note`를 승계·덮어쓰기 규칙으로 정의.
  §결정 2 파급표에 `PipelineRestarter` 행 추가(재시작 가능 상태가 양성 목록이라
  `AWAIT_APPROVAL`은 자동 배제 — 코드 변경 불요).
