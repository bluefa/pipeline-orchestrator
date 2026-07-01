# pipeline-orchestrator

The **ADR-016 install/delete pipeline domain model** —
[PR #511](https://github.com/bluefa/pii-agent-demo/pull/511), the *domain half* of the design.

A pipeline runs an ordered chain of tasks for **one target** to **INSTALL or DELETE**
infrastructure. Each task is a `TERRAFORM_JOB` (dispatch a Terraform job, poll until it
finishes) or a `CONDITION_CHECK` (poll until a condition is met). The pipeline survives
process restarts, never runs two active pipelines for the same target, retries a task a
few times before failing it, and can be cancelled.

> The **database row is the only state.** A restart simply resumes from the rows.

## Scope — domain (ADR-016) + execution (ADR-021)

This module is the **durable domain model** (ADR-016: state, data model, uniqueness rule, failure
semantics, lifecycle) **and** the **claim-pull execution model** (ADR-021: how the state machine is
driven forward). The two stay in separate ADRs so the runtime can be superseded without touching the
domain.

The execution model is a **claim-pull, two-transaction loop** (ADR-021): N worker threads pull due
pipelines from the DB via `FOR UPDATE SKIP LOCKED` + a lease/fencing token (tx1), run the external
InfraManager call **outside any transaction**, then commit a guarded write-back (tx2). There is no
leader election, no replica constraint, and no in-memory in-flight guard — the DB claim is the only
coordination primitive, so it is multi-pod safe by construction. Crash recovery is automatic via lease
expiry; cancel is immediate for an idle pipeline and cooperative for a running one.

- [ADR-016](docs/adr/016-install-delete-pipeline-domain-model.md) — the domain half
- [ADR-021](docs/adr/021-pipeline-execution-model.md) — the execution half (implemented here)
- [docs/adr021/implementation-dod.md](docs/adr021/implementation-dod.md) — per-Decision scope & DoD

## Stack

- Java 21, Spring Boot 3.3.5, Spring Data JPA
- **MySQL 8** in production; schema (tables, indexes, unique constraints) is generated from
  JPA entity annotations — no Flyway, no hand-written SQL migrations.
- Tests run on H2 in MySQL-compatibility mode (no Docker required).

## Layout

```
com.bff.pipeline
├── (root)       App bootstrap: PipelineApplication
├── config/      Typed settings + bean wiring: PipelineConfig, PipelineSettings, ExecutionSettings
├── entity/      JPA entities (Pipeline, Task, TaskAttempt, TaskCheck)
├── enums/       Domain enums (PipelineStatus/Type, TaskStatus/Operation, ErrorCode, CheckSignal)
├── dto/         External transport values (TerraformPoll, ErrorResponse)
├── model/       Domain value/contract types (TaskType, TaskProgress, Recipe, StepOutcome)
├── service/     @Component/@Service beans only, grouped by concern:
│   ├── execution/  ADR-021 claim-pull runtime: PipelineScheduler, PipelineClaimer,
│   │               PipelineWorker, StepRunner, StepReporter
│   ├── lifecycle/  pipeline creation & admin cancel: PipelineCreator, PipelineInserter,
│   │               PipelineControl, Recipes
│   └── task/       task transitions/types/observation: TaskStateMachine, TaskTypeRegistry,
│                   TaskCanceller, ObservationRecorder, ConditionCheckTask, terraform/TerraformTask
├── client/      InfraManager boundary:
│   │             InfraManagerClient (닫힌 계약) + TimeBoundedInfraManagerClient (호출별 타임아웃 데코레이터)
│   │             + InfraManagerFeignClient (@FeignClient) + InfraManagerFeignAdapter (delegate)
│   │             + InfraManagerOperationRegistry (operation→바인딩, 부팅 검증)
│   ├── terraform/  TERRAFORM_JOB operation 바인딩: TerraformOperationBinding + Apply/DestroyNetworkBinding
│   └── condition/  CONDITION_CHECK operation 바인딩: ConditionOperationBinding + NetworkReadyBinding
├── repository/  Spring Data repositories (guarded-CAS transitions)
├── advice/      GlobalAdvice (@RestControllerAdvice — REST exception handler)
├── exception/   OrchestrationException(HTTP 매핑) 계열 + InfraManager 전송 경계 닫힌 어휘
│                 (CallTimeout/CallInterrupted/CallFailedException — StepRunner가 ErrorCode로 변환)
└── utils/       Static helpers (TaskSettingsResolver — effective per-task setting resolution + deadlines)
```

Each task's behaviour is a **`TaskType`** (`model/`): `TerraformTask` (in `service/terraform/`) and
`ConditionCheckTask` implement the `execute` → `check` lifecycle, and `TaskStateMachine` resolves a task
row to its type **by name** through `TaskTypeRegistry` — so a new kind of task is a new self-registering
implementation, not an edit to a `switch`. An unknown name fails the task with `ErrorCode.UNKNOWN_TASK`.
The external call (`execute`/`check`) is performed by `StepRunner` outside any transaction; its result
(`StepOutcome`) is applied to the task row by `TaskStateMachine.applyOutcome` inside the tx2 write-back.

## Extending — 새 task 추가하기

먼저 4개 축을 구분하면 어디를 건드릴지 명확해진다:

| 축 | 무엇 | 예 | 어디 |
|---|---|---|---|
| **mechanism** = `TaskType` | task를 *실행*하는 방식(dispatch/poll 패턴). 새 종류는 드묾 | `TERRAFORM_JOB`, `CONDITION_CHECK` | `service/task/…` (`@Component` 자기등록) |
| **operation** | 도메인 액션. mechanism을 유일하게 결정 | `APPLY_NETWORK`, `NETWORK_READY` | `enums/TaskOperation` |
| **API 바인딩** | 그 operation이 부르는 InfraManager 실제 API + 응답→공통형 변환 | `ApplyNetworkBinding` | `client/terraform` · `client/condition` |
| **정체성/조합** | `TaskDefinition`(이름·metadata), `RecipeDefinition`(순서 있는 step 목록) | `APPLY_NETWORK_V1` | `enums/` |

**핵심 원칙:** 어떤 축을 늘리든 `switch`를 고칠 일이 없다. 모든 확장은 **자기등록 + 부팅/CI 검증**이라, 빠뜨리면 런타임이 아니라 **컴파일 또는 부팅**에서 걸린다.

### A. 기존 mechanism에 새 operation 추가 (가장 흔함)

예: terraform으로 서브넷을 만드는 `APPLY_SUBNET` 추가.

1. **`enums/TaskOperation`** — 상수 추가. mechanism을 지정한다:
   ```java
   APPLY_SUBNET(Mechanism.TERRAFORM_JOB),
   ```
2. **`client/InfraManagerFeignClient`** — 그 operation의 실제 API 메서드 + **전용 응답 DTO**(`dto/`)를 추가한다. operation마다 경로·응답이 다르므로 공유하지 않는다:
   ```java
   @PostMapping("/infra/subnet/apply")           ApplySubnetResponse applySubnet(@RequestParam("target") String target);
   @GetMapping("/infra/subnet/apply/jobs/{jobId}") ApplySubnetStatusResponse applySubnetStatus(@PathVariable String jobId);
   ```
3. **`client/terraform/ApplySubnetBinding`** — `@Component`로 `TerraformOperationBinding`을 구현한다. **컴파일러가 무엇을 짜야 하는지(operation/dispatchJobIds/poll) 강제한다.** null·누락·불가능 조합은 `TerraformOperationBinding`의 static 헬퍼(`requireJobIds`/`toPoll`)로 `CallFailedException`으로 닫는다. (CONDITION_CHECK operation이면 `client/condition`에 `ConditionOperationBinding` 구현.)
4. **`enums/TaskDefinition`** — 명명된 정체성 + metadata 추가(버전 접미사 `_V1` 불변 규약):
   ```java
   APPLY_SUBNET_V1(CloudProvider.AWS, TaskOperation.APPLY_SUBNET, "서브넷 생성", "…"),
   ```
5. **`enums/RecipeDefinition`** — 그 `TaskDefinition`을 알맞은 recipe의 `steps`에 넣는다(또는 새 recipe 항목).
6. **`mvn test`** — 검증이 자동으로 강제한다:
   - `InfraManagerOperationRegistry`가 부팅 시 "모든 TERRAFORM_JOB operation이 정확히 하나의 바인딩을 갖는가"를 검사 → 바인딩 빠뜨리면 **부팅 실패**, `InfraManagerOperationRegistryTest`가 **CI에서 red**.
   - `TaskTypeRegistry`가 operation의 mechanism이 실제 `TaskType`을 가리키는지 검사.
   - `RecipeCatalog`가 recipe의 provider와 step provider 일치를 부팅 검증.

> 어댑터(`InfraManagerFeignAdapter`)나 그 어떤 `switch`도 건드리지 않는다 — 바인딩이 자기등록된다.

### B. 새 mechanism = 새 `TaskType` 추가 (드묾)

예: 스크립트를 실행하는 `SCRIPT_JOB`.

1. **`enums/TaskOperation`** — `Mechanism.SCRIPT_JOB = "SCRIPT_JOB"` 상수 + 그 mechanism을 쓰는 operation들을 추가. slot을 소비하면 `TaskOperation.consumesTerraformSlot()`(현재 `TERRAFORM_JOB`만 true) 판별을 조정한다.
2. **`service/task/…`** — `TaskType`을 `@Component`로 구현한다. `taskName()`은 `Mechanism.SCRIPT_JOB`을 반환하고 `execute`/`check` 라이프사이클을 채운다(비즈니스 실패는 예외가 아니라 `TaskProgress` 값, 호출 실패만 `RuntimeException`). 자기등록되며 `TaskTypeRegistry`가 부팅 검증한다.
3. **InfraManager 계약** — 새 mechanism이 InfraManager를 새 모양으로 부르면 `InfraManagerClient`에 메서드를 추가하고(→ 데코레이터 passthrough → 어댑터 위임), 그 mechanism용 바인딩 인터페이스 + `InfraManagerOperationRegistry` 검증을 더한다. 기존 terraform/condition 모양을 재사용하면 그대로 쓴다.
4. **`enums/TaskDefinition` · `RecipeDefinition`** — A와 동일하게 정체성·조합을 등록한다.

### 잊으면 어떻게 되나 (fail-fast 지점)

| 빠뜨린 것 | 언제 잡히나 |
|---|---|
| 바인딩 인터페이스 메서드 미구현 | **컴파일 에러** |
| operation에 바인딩 없음 / mechanism 불일치 / 중복 | **부팅 실패** + `InfraManagerOperationRegistryTest`(CI) |
| operation의 mechanism에 `TaskType` 없음 | **부팅 실패**(`TaskTypeRegistry`) |
| recipe provider ≠ step provider | **부팅 실패**(`RecipeCatalog`) |
| 저장된 task 이름이 미해석 | 런타임에 `ErrorCode.UNKNOWN_TASK`로 깨끗이 열화 |

## Documentation

- [docs/adr/016-…](docs/adr/016-install-delete-pipeline-domain-model.md) — the domain model
- [docs/exception-strategy.md](docs/exception-strategy.md) — how external-call failures and
  business-rule failures are separated and handled
- [docs/extensibility.md](docs/extensibility.md) — the v1 seams for more task kinds, task
  post-checks, and an event outbox
- [docs/acceptance-criteria.md](docs/acceptance-criteria.md) — the ADR-derived definition of
  done and the review log

## Build & test

```bash
mvn test       # runs on H2 (MySQL mode); no external services needed
mvn package    # builds the jar
```
