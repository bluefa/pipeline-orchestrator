# Task/Recipe 카탈로그 · DTO · Admin API 확장 설계 (Azure/IDC/AWS/GCP × Install/Delete)

> 상태: **설계문서(계획)** — 코드 변경 없음. ADR-016/021을 무너뜨리지 않고, 이미 예고된 seam
> (`TaskOperation` enum→registry, recipe의 "(type, provider)별 기본값")을 실제로 여는 방향을 확정한다.
> YAGNI: 실제 필요가 확인된 것만 순서대로 구현한다. (codex 설계 리뷰 1라운드 반영본 — §9)

## 0. 핵심 결정 요약

1. **동작과 정체성을 분리한다.** 동작(`TaskType`)은 소수(2~3) 고정, 변형은 **명명된 카탈로그 항목**으로 늘린다.
   18개 동작 클래스가 아니라 `TerraformTask` 하나를 가리키는 18개 **`TaskDefinition`** 항목.
2. **Task도 Recipe도 1급 카탈로그로 관리하고 metadata를 API로 노출한다.** metadata는 전부 **코드에서 관리**(정적,
   DB CRUD 없음) → Admin API는 **읽기 전용**.
3. **single-owner 불변식 불변.** *Target 하나당 running pipeline은 하나.* `active_target` 유니크 제약은
   **target-only 유지**. **target이 전역 유일 정체성이고, provider는 그 target에서 파생/검증되는 속성**이지
   독립 선택 축이 아니다(§3). → `AWS+targetA`와 `GCP+targetA`는 별개 target이 아니라, targetA가 이미 한 provider에
   속한다. 같은 target에 provider 불일치 recipe를 걸면 검증 실패.
4. **DB엔 enum 상수 이름을 저장하고, 정의 이름에 버전을 박아 불변으로 만든다.** row는 `TaskDefinition` **enum 상수
   이름**(`AWS_APPLY_NETWORK_V1`)을 **String 컬럼 + 수동 resolver**로 저장한다(`@Enumerated` 금지 — 삭제된 상수는
   Hibernate 매핑 예외로 row/쿼리 로드를 터뜨린다). 이름이 **버전 불변**이라 V1 의미는 절대 안 바뀐다(바뀌면 V2 추가).
   미해석 이름(삭제/rename)은 `TaskDefinition.find(name): Optional` → `UNKNOWN_TASK`로 깨끗이 열화(§9-P1).
   - **실행 투영은 row에 유지**: `taskName`(mechanism)/`operation`은 **현행대로 row에 저장**한다 — `TaskType.execute/check`가
     `task.getOperation()`을 읽으므로(시그니처 무변경). 정의가 불변이라 이 투영값은 **drift하지 않는다**(스냅샷의 위험 없이
     스냅샷의 안정성만 취함). `task_definition`은 그 위에 얹는 **정체성/카탈로그 링크**.
   - slot 카운트는 row에 박은 `consumes_terraform_slot` boolean으로(§4, 카탈로그·삭제와 무관, §9-P2).
5. **DTO는 영속 컬럼이 아니라 TaskType 내부 파싱 경계에 둔다.** `task_attempt.response`는 raw text 유지.
6. **`TaskOperation` enum은 유지**한다(닫힌 채). `TaskDefinition`이 이를 *참조*할 뿐 대체·삭제하지 않는다 —
   InfraManager 경계는 계속 `(provider, operation)`를 받는다.

## 0.1 축 정리

| 축 | 무엇 | 표현 | 현재 | 확장 시 |
|---|---|---|---|---|
| A. 메커니즘 | *어떻게* dispatch/poll | `TaskType` 인터페이스 | 2개 | **거의 안 늘어남 (2~3)** |
| B. 액션+정체성 | *무슨* 작업, 명명·메타데이터 | `(taskName, operation)` | 흩어짐 | **`TaskDefinition` 버전 불변 enum으로 승격, row엔 이름(String) 저장** |
| C. job 종류 | terraform 완료판정 | `TerraformJob` 인터페이스 | 1개 | 드물게 1~2 |
| D. provider | AWS/Azure/GCP/IDC | 없음 | — | **카탈로그 메타데이터, target에서 파생(격리 축 아님)** |
| E. recipe | 파이프라인 정체성 | `Recipes`+`PipelineType` switch | 하드코딩 2개 | **`Recipes` 안 `Map<RecipeKey,RecipeDefinition>`으로 승격** |

---

## 1. TaskDefinition 카탈로그 — 명명된 1급 task 정체성 + 메타데이터

**동작 = `TaskType`**(`TerraformTask`/`ConditionCheckTask` 소수 유지), **정체성 = `TaskDefinition`**(명명·열거·metadata).
`AWS_APPLY_NETWORK`, `GCP_DESTROY_NETWORK` … 각 항목이 곧 "AwsApplyTerraformTask"의 실체.

| 필드 | 예 | 용도 |
|---|---|---|
| enum 상수 이름 (버전 불변) | `AWS_APPLY_NETWORK_V1` | recipe·Task row가 String으로 저장·참조하는 안정 키 |
| `displayName`, `description` | "AWS 네트워크 생성" | **API 메타데이터** |
| `provider` | `AWS` | 표시/필터 |
| `mechanism` | `TERRAFORM_JOB` (= `taskName`) | 어느 `TaskType`이 실행 → registry 해석 |
| `operation` | `APPLY_NETWORK` | InfraManager 호출 인자(스냅샷됨) |
| 파생 flag | `consumesTerraformSlot` | mechanism에서 파생 |

- **형태**: 시작은 **enum 기반**(상수마다 metadata+mechanism). 구성형 요구 오면 registry(config/DB) 승격.
- **버전 불변 규약**: 상수 이름에 버전(`_V1`)을 박아 **의미를 불변**으로 둔다. 바뀌면 `_V2`를 추가하고 `_V1`은 참조 row가
  사라질 때까지 남긴다. 이 불변성이 codex P1(배포 간 in-flight 시맨틱 변화)을 원천 차단한다.
- **영속**: enum 자체가 아니라 상수 **이름(String)** 을 저장하되 **`@Enumerated` 금지**, 수동
  `TaskDefinition.find(name): Optional`로 해석 — 삭제/rename된 이름은 예외 대신 `Optional.empty()` → `UNKNOWN_TASK` 열화.

---

## 2. RecipeDefinition — 명명된 1급 파이프라인 정체성 + 메타데이터

`Recipes.forType(PipelineType)` **switch** → **`Recipes` 안 `Map<RecipeKey, RecipeDefinition>`**. (인터페이스/별도
`RecipeCatalog` 타입 안 만듦 — 단일 구현이라 과설계, §9-P2.)

`RecipeDefinition` 항목:

| 필드 | 예 | 용도 |
|---|---|---|
| enum 상수 이름 (버전 불변) | `AWS_NETWORK_INSTALL_V1` | 조회·API 키, Pipeline에 String으로 저장 |
| `displayName`, `description` | "AWS 네트워크 설치" | **API 메타데이터** |
| `provider`, `pipelineType` | `AWS`, `INSTALL` | 분류·`RecipeKey` |
| `steps` | `[AWS_APPLY_NETWORK, AWS_NETWORK_READY]` | `TaskDefinition` id 순서 목록 |

- **버전 불변**: TaskDefinition과 동일 규약(`_V1`, String 저장, `Recipes.find(name): Optional`, `@Enumerated` 금지).
- **조회**: `RecipeKey(provider, pipelineType)` → RecipeDefinition, 또는 recipe 이름으로 직접.
- **부팅 검증(switch exhaustiveness 대체, §9-P1)**: 부팅 시 fail-fast로 (1) 카탈로그가 **광고하는(=API 노출·create 허용)**
  모든 `(provider, pipelineType)` 조합 존재, (2) 각 step의 `TaskDefinition` 이름 해석 가능, (3) **recipe.provider == 모든
  step TaskDefinition.provider**(§3), (4) 각 step 정의의 `mechanism`이 `TaskTypeRegistry`에 **등록된 `TaskType`을 가짐**
  (광고된 카탈로그 항목이 런타임 확정 실패로 부팅되지 않게, §9-P2) 검증. "모든 provider×type 8종 강제"가 아니라
  **선언된 지원 집합**만(미지원 조합은 create에서 400).

---

## 3. Provider/Target 정체성 (§9-P1 해결)

- **target은 전역 유일 파이프라인 정체성.** `active_target` 유니크 제약 target-only 유지, provider를 키에 넣지 않는다.
- **provider는 targetSourceId로 외부 조회해 얻는다.** target은 targetSourceId 기반이고, 그 target에 수행 가능한 cloud
  provider는 **기존 `InfraManagerClient`(프로덕션은 Feign) 호출**로 결정된다 — 별도 클라이언트를 만들지 않고 인터페이스에
  `cloudProvider(targetSourceId): CloudProvider` 조회를 추가한다. enum `CloudProvider { AWS, GCP, AZURE, IDC }`,
  Pipeline 필드명 `cloudProvider`.
- **create 시점에만 한 번 조회**하고 결과를 `Pipeline.cloudProvider`에 영속 → claim-pull 실행 경로(hot path)는 이 조회에
  의존하지 않는다(ADR-021 영향 0).
- **실패 계약**(exception-strategy 정합): provider를 못 찾음/모호 → **400**; 조회 서버 장애/타임아웃 → **503**(인프라
  실패, 예외 전파). create 입력이 provider를 보내면 조회값과 일치하는지만 확인(불일치 400), 없으면 조회값 사용.
- provider는 recipe 선택·라우팅·표시용이지 격리 축이 아니다.
- 결과: `AWS+targetA` / `GCP+targetA` 애매성 없음 — targetA의 provider는 조회가 결정. single-owner는 target만으로 성립.
- create 경로(`PipelineCreator`/`PipelineInserter`): targetSourceId→provider 조회 → `RecipeKey(provider, pipelineType)`로
  RecipeDefinition 조회, 없으면 400.
- **부팅 검증에 provider 정합성 추가(§9-P1)**: recipe의 `provider`와 그 recipe가 참조하는 **모든 step `TaskDefinition`의
  `provider`가 일치**하는지 부팅 시 fail-fast. (AWS recipe가 실수로 GCP task 정의를 품는 걸 막는다.)

---

## 4. Task row 영속 & InfraManager 계약 (§9-P1/P2 해결)

**진실원(source of truth) = `task_definition`.** 나머지 두 컬럼은 insert 때 정의에서 **원자적으로 파생된 write-once
캐시**이며 독립적으로 쓰이지 않는다 → 의미상 diverge 불가(불변 정의 + 동시 기록). 권위 순서: `task_definition` 해석이
먼저, 투영 컬럼은 캐시.

Task row 컬럼(현행 + 추가):
- `task_definition`(String = `AWS_APPLY_NETWORK_V1`) — **추가, 진실원**. 정체성/카탈로그 링크. `@Enumerated` 금지,
  `TaskDefinition.find(name):Optional`로 해석. 미해석(삭제/rename) → `UNKNOWN_TASK`(read 안 터짐).
- `taskName`(mechanism), `operation` — **현행 유지, 파생 캐시**. `TaskType.execute/check`가 `task.getOperation()`을
  읽으므로 **TaskType 시그니처 무변경**. insert 때 정의에서 채움.
- `consumes_terraform_slot`(boolean) — **추가, 파생 캐시**. slot 게이트는 `WHERE consumes_terraform_slot AND
  status='IN_PROGRESS'`로 카운트(**(b) 결정**). 카탈로그 변경·정의 삭제와 무관하게 robust(삭제 정의의 in-flight row도
  정확히 셈). 기존 `countByTaskNameInAndStatus`를 이 쿼리로 교체. ADR-021 Decision 7 soft gate 시맨틱 동일 — 기준만 boolean.

**StepRunner 사전 일관성 단언(§9-P1)**: 외부 호출 **전에** (1) `task_definition` 해석 → 실패 시 `unknownTask`;
(2) 해석된 정의의 mechanism registry 미스 → `unknownTask`; (3) 정의의 `operation`/mechanism/slot-flag가 row의 캐시
컬럼과 **일치하는지 단언** → 불일치(=DB 손상/버그)면 외부 호출 전에 영속 실패(`CHECK_ERROR`)로 끊는다. 셋 다 task
구현 호출 전 지점(현행 registry 미스와 동일).

**InfraManager 계약 불변**: 클라이언트는 계속 `(target, operation)`(+필요 시 provider)만 받는다. **카탈로그 항목 전체를
클라이언트에 넘기지 않는다** — UI/메타데이터가 외부 경계에 새는 걸 막는다(§9-P1).

**데이터 마이그레이션 / cutover(§9-P1/P2)**: 이 리포는 Flyway/raw SQL 금지(AGENTS.md)라 기존 row 백필 불가. 이 기능은
**미출시 INSTALL/DELETE 확장의 추가분**이므로 **drain-before-deploy** 런북:
- 신규 create 차단 → 기존 파이프라인 **RUNNING 0**까지 드레인 → **구버전 워커/스케줄러가 더는 어떤 row도 claim하지
  않음**을 보장(ADR-021은 rolling overlap을 허용하므로 구버전 워커 종료를 명시적으로 확인) → 배포.
- 새 컬럼은 **nullable**로 추가한다(JPA가 non-null 컬럼을 백필 없이 붙이는 위험 회피). 과거 terminal row는 새 컬럼이
  null이어도 무해 — IN_PROGRESS가 아니라 slot 카운트에 안 잡히고, 재실행되지 않으므로 `UNKNOWN_TASK` 경로도 안 탄다.
- 드레인 불가 환경이 되면 그때 staged 백필 잡을 별도 설계.

---

## 5. Response DTO 경계 (dispatch DTO + poll outputs)

| 경계 | 방침 |
|---|---|
| 엔진 ↔ 영속(`task_attempt.response`) | **raw text 유지** — 형식 불투명이 새 task 종류를 마이그레이션 없이 허용. |
| TaskType 내부(text → 타입값) | **명명 DTO를 세운다** — 파싱 경계 승격. |

1. **`TerraformDispatchResponse` DTO** — `check()`의 `List<String>` 인라인 파싱을 명명 경계로. outputs 붙으면 확장.
2. **poll DTO 확장은 필요 기반** — 내부가 terraform outputs를 소비할 때만 `TerraformPoll` 확장/별도 `TerraformOutputs`.
   (codex도 이 방향 OK.)

---

## 6. TerraformJob seam
`aggregate()`는 다형 소비 지점이나 `check()`가 `new JobIdTerraformJob` 하드코딩(생성 지점 고정). 두 번째 job 종류가
실제로 생기면 §5의 `TerraformDispatchResponse`에서 알맞은 구현을 만드는 **작은 팩토리**를 세운다. 그전까지 YAGNI.

---

## 7. Admin API — 읽기 전용, 코드 카탈로그 + DB 인스턴스

metadata가 코드 관리 정적값이므로 **카탈로그 API는 순수 read**, **인스턴스 API는 DB read**. 두 계층 DTO를 분리한다.

### 7.1 카탈로그(정적) — "무슨 파이프라인/task를 지원하나"
| 메서드 | 경로 | 반환 |
|---|---|---|
| GET | `/admin/recipes` | 모든 `RecipeDefinition` 요약(id, displayName, provider, pipelineType, step 수) |
| GET | `/admin/recipes/{recipeId}` | recipe 상세 + 순서 step마다 `TaskDefinition` metadata 인라인 |
| GET | `/admin/task-definitions` | 모든 `TaskDefinition` 요약(id, displayName, provider, mechanism, operation, consumesTerraformSlot) |
| GET | `/admin/task-definitions/{taskDefId}` | 단일 정의 상세 |

- 소스는 코드 카탈로그(§1/§2). 필터 파라미터 `?provider=AWS&pipelineType=INSTALL` 정도만(선택). DB 안 침.

### 7.2 인스턴스(런타임) — "이 실행이 정확히 무슨 파이프라인인가"
| 메서드 | 경로 | 반환 |
|---|---|---|
| GET | `/admin/pipelines/{id}` | Pipeline: target, provider, pipelineType, `recipeId`+recipe metadata, status, 타이밍, task 요약 목록 |
| GET | `/admin/pipelines/{id}/tasks` | 그 파이프라인의 Task 목록: status, `taskDefinitionId`+metadata, failCount, 최신 attempt 요약 |
| GET | `/admin/tasks/{taskId}` | 단일 Task 상세: 스냅샷(taskName/operation), 해석된 TaskDefinition metadata, attempt 이력(attemptNumber/status/errorCode/타이밍) |

- 각 인스턴스 응답은 **엔티티 + 카탈로그 metadata를 조인**해 직렬화(row의 `recipeId`/`taskDefinitionId`로 카탈로그 조회).
- `taskDefinitionId`가 미해석(삭제된 옛 id)이면 metadata는 `null`/`"(unknown)"`로 degrade하고 스냅샷값은 그대로 노출.
- 목록 API는 페이징(파이프라인 목록이 필요하면 `GET /admin/pipelines?status=&provider=&page=`) — 실수요 시 추가.

### 7.3 DTO 계층
- `RecipeSummaryDto` / `RecipeDetailDto`(+ `RecipeStepDto` with TaskDefinition metadata)
- `TaskDefinitionDto`
- `PipelineViewDto`(+ `TaskViewDto`, `TaskAttemptDto`) — 엔티티→뷰 변환, 카탈로그 조인
- 카탈로그 DTO와 인스턴스 DTO를 섞지 않는다(정적 vs 상태).

---

## 8. 구현 스케치 (최소 변경)

| 신규/변경 | 무엇 |
|---|---|
| `enum Provider` | AWS/AZURE/GCP/IDC. 격리 축 아님, 카탈로그/파생 메타데이터. |
| `enum TaskDefinition` | §1. `_V1` 버전 불변 이름, `display/desc/provider/mechanism(taskName)/operation/flags`. `TaskOperation` 참조. `find(name):Optional`. |
| `RecipeDefinition` + `Recipes` 확장 | §2. `Map<RecipeKey, RecipeDefinition>` + 부팅 검증. switch 제거. `find(name):Optional`. |
| `RecipeStep` 변경 | `TaskDefinition` 이름 참조로. |
| `Task` 엔티티 | `taskName`/`operation` **유지**(실행 투영) + `task_definition`(String, `@Enumerated` 금지) + `consumes_terraform_slot`(boolean) 컬럼 추가. |
| `Pipeline` 엔티티 | `recipe_definition`(String) + `provider` 저장. **유니크 제약(target-only) 불변.** |
| `PipelineInserter`/`PipelineCreator` | provider 검증(§3), recipe로 step 확장 시 `task_definition`+`consumes_terraform_slot` 채움. |
| `StepRunner` | `task_definition` → `TaskDefinition.find` → mechanism(taskName)→registry. 미해석 시 `UNKNOWN_TASK`. |
| `PipelineWorker`/`TaskRepository` | slot 카운트를 `countByConsumesTerraformSlotTrueAndStatus`(boolean 기반)로 교체. |
| `TerraformDispatchResponse` DTO | §5. `check()` 파싱 경계. |
| Admin 컨트롤러 + 뷰 DTO | §7. 읽기 전용. |

> `TaskOperation`/`UNKNOWN_TASK` 경로는 유지. slot 쿼리는 `task_name` 기반 → `consumes_terraform_slot` boolean 기반으로 교체.

---

## 9. codex 설계 리뷰 반영

### 9.3 라운드 3 반영
- **P1 double source of truth** → §4: `task_definition`을 **단일 진실원**으로 명시, 투영 컬럼은 write-once 캐시,
  StepRunner가 외부 호출 전 정의↔캐시 일관성 단언(불일치 시 `CHECK_ERROR`).
- **P1 provider 조회 실패 계약** → §3: InfraManagerClient 외부 조회(cloudProvider), not-found/모호→400, 조회 장애→503.
- **P2 drain cutover 세부** → §4 런북: 신규 create 차단·구버전 워커 종료 확인·새 컬럼 nullable.
- **P2 부팅 mechanism 검증** → §2(4): 광고된 step 정의의 mechanism이 registry에 등록된 `TaskType`을 갖는지 부팅 검증.

### 9.2 라운드 2 반영 (revised)
- **P1 데이터 마이그레이션** → §4 말미: Flyway 금지 환경, **drain-before-deploy**(RUNNING 0) 요구, 미출시 확장의 추가분.
- **P1 실행 계약(no-snapshot 모순)** → §0-4/§4: `taskName`/`operation` **row 유지**(실행 투영, TaskType 시그니처 무변경),
  StepRunner가 정의 미스·registry 미스 **둘 다 호출 전 `unknownTask` 매핑**.
- **P1 provider 정합성** → §3: cloudProvider 외부 조회가 권위, request provider는 assertion, 부팅 시
  recipe.provider == step 정의 provider 검증.
- **P2 Admin degrade 모순** → §7: `taskName`/`operation` 투영이 row에 남으므로 미해석 정의도 스냅샷값 노출과 정합.

### 9.1 라운드 1 반영
- **P1 provider/target 정체성** → §3: target 전역 유일, provider는 파생/검증, active_target target-only 근거 명시.
- **P1 in-flight 시맨틱 & UNKNOWN_TASK** → §0-4/§4: **버전 불변 이름**(`_V1`)으로 시맨틱 고정(스냅샷 불필요),
  String 저장 + `find(name):Optional`(`@Enumerated` 금지)로 삭제 시 `UNKNOWN_TASK` 열화.
- **P1 recipe 부팅 검증 범위** → §2: "선언된 지원 집합" 검증, 미지원 조합 create 400.
- **P1 InfraManager 계약** → §4: 클라이언트는 `(target, operation)`만, 카탈로그 항목 안 넘김, `TaskOperation` 유지.
- **P2 과설계** → §2: `RecipeCatalog` 인터페이스 대신 `Recipes` 내부 `Map`, `TaskOperation` 유지.
- **P2 slot 쿼리** → §4: **(b) 결정** — row에 `consumes_terraform_slot` boolean을 박고 boolean 기반 카운트로 교체.
  카탈로그 변경·정의 삭제와 무관하게 robust(삭제된 정의의 in-flight row도 정확히 셈).

## 10. 마이그레이션 순서
1. `enum Provider` + `enum TaskDefinition`(버전 불변 이름). `RecipeStep`→정의 이름 참조. `Task.task_definition` +
   `consumes_terraform_slot` 컬럼. slot 쿼리 boolean 기반으로 교체.
2. `RecipeDefinition`/`Recipes` `Map` 확장 + 부팅 검증. `Pipeline.recipe_definition/provider`. create provider 검증.
3. `TerraformDispatchResponse` DTO.
4. Admin API(§7) — 카탈로그 read → 인스턴스 read 순.
5. **필요 시** poll outputs DTO, 두 번째 `TerraformJob` 팩토리.

## 11. ADR 영향
- ADR-016 §2: recipe "(type, provider)별 코드 기본값" 문구를 구현이 따라잡음.
- `TaskOperation`: **유지**(닫힘). 카탈로그가 참조만 함 → `extensibility.md` 경로와 모순 없음, 오히려 보수적.
- ADR-021 single-owner: *target 하나당 running 하나* **불변**, active_target target-only 유지.
- 결론: **ADR을 깨지 않음.** 열어둔 seam을 실제로 여는 작업.
