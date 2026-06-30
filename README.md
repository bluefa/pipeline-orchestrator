# pipeline-orchestrator

The **ADR-016 install/delete pipeline domain model** —
[PR #511](https://github.com/bluefa/pii-agent-demo/pull/511), the *domain half* of the design.

A pipeline runs an ordered chain of tasks for **one target** to **INSTALL or DELETE**
infrastructure. Each task is a `TERRAFORM_JOB` (dispatch a Terraform job, poll until it
finishes) or a `CONDITION_CHECK` (poll until a condition is met). The pipeline survives
process restarts, never runs two active pipelines for the same target, retries a task a
few times before failing it, and can be cancelled.

> The **database row is the only state.** The domain exposes one operation —
> `PipelineEngine.advance(pipelineId)` — that moves a pipeline's state machine forward one
> step. A restart simply resumes from the rows.

## Scope — domain only (ADR-016), not execution (ADR-021)

This module is the **durable domain model**: the state, the data model, the uniqueness rule,
the failure semantics, and the lifecycle. The **execution model** — *when, how often, and with
what concurrency* `advance()` is called (the runner, scheduling, worker pool, crash recovery) —
is the separate, independently-revisable **ADR-021** and is **deliberately not in this repo** (only
[ADR-016](docs/adr/016-install-delete-pipeline-domain-model.md), the domain half, lives here). There is
no scheduler and no reconciler loop here; an
ADR-021 runner drives the engine. Tests drive `advance()` directly.

## Stack

- Java 21, Spring Boot 3.3.5, Spring Data JPA
- **MySQL 8** in production; schema (tables, indexes, unique constraints) is generated from
  JPA entity annotations — no Flyway, no hand-written SQL migrations.
- Tests run on H2 in MySQL-compatibility mode (no Docker required).

## Layout

```
com.bff.pipeline
├── (root)       App bootstrap + wiring: PipelineApplication, PipelineConfig, PipelineSettings
├── entity/      JPA entities (Pipeline, Task, TaskAttempt, TaskCheck)
├── enums/       Domain enums (PipelineStatus/Type, TaskStatus/Operation, ErrorCode, CheckSignal)
├── dto/         External transport values (TerraformPoll, ErrorResponse)
├── model/       Domain value/contract types (Recipe, RecipeStep, TaskProgress)
├── recipe/      Code-default recipes (Recipes — a static definition, not a bean)
├── service/     @Component/@Service beans, grouped by feature:
│   ├── pipeline/  PipelineEngine (advance one step), PipelineControl (cancel), PipelineCreator, PipelineInserter
│   ├── task/      TaskStateMachine, TaskCanceller, TaskTypeRegistry, TaskAuditWriter
│   └── task/type/ TaskType SPI + its implementations (TerraformTask, ConditionCheckTask)
├── client/      InfraManager boundary (InfraManagerClient) + its exception contract
├── repository/  Spring Data repositories (guarded-CAS transitions)
├── controller/  GlobalAdvice (REST exception handler) — the REST layer added later
└── utils/       Static helpers (TaskSettings — effective per-task setting resolution + deadlines)
```

Each task's behaviour is a **`TaskType`** (`service/task/type/`): `TerraformTask` and `ConditionCheckTask`
(same package) implement the `execute` → `check` lifecycle (and may override the default no-op `postCheck`),
and `TaskStateMachine` resolves a task row to its type **by name** through `TaskTypeRegistry` — so a new kind of task is a new self-registering implementation, not an edit to a
`switch`. An unknown name fails the task with `ErrorCode.UNKNOWN_TASK`.

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
