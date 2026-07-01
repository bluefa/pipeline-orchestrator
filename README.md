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
├── client/      InfraManager boundary (InfraManagerClient) + per-call-timeout decorator
├── repository/  Spring Data repositories (guarded-CAS transitions)
├── controller/  GlobalAdvice (REST exception handler) — the REST layer added later
└── utils/       Static helpers (TaskSettings — effective per-task setting resolution + deadlines)
```

Each task's behaviour is a **`TaskType`** (`model/`): `TerraformTask` (in `service/terraform/`) and
`ConditionCheckTask` implement the `execute` → `check` lifecycle, and `TaskStateMachine` resolves a task
row to its type **by name** through `TaskTypeRegistry` — so a new kind of task is a new self-registering
implementation, not an edit to a `switch`. An unknown name fails the task with `ErrorCode.UNKNOWN_TASK`.
The external call (`execute`/`check`) is performed by `StepRunner` outside any transaction; its result
(`StepOutcome`) is applied to the task row by `TaskStateMachine.applyOutcome` inside the tx2 write-back.

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
