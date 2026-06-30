# pipeline-orchestrator

The **ADR-016 install/delete pipeline domain model** and **ADR-021 claim-pull execution layer** —
[PR #511](https://github.com/bluefa/pii-agent-demo/pull/511).

A pipeline runs an ordered chain of tasks for **one target** to **INSTALL or DELETE**
infrastructure. Each task is a `TERRAFORM_JOB` (dispatch a Terraform job, poll until it
finishes) or a `CONDITION_CHECK` (poll until a condition is met). The pipeline survives
process restarts, never runs two active pipelines for the same target, retries a task a
few times before failing it, and can be cancelled.

> The **database row is the only state.** The execution layer drives pipelines forward via the
> **claim-pull two-transaction cycle**: a worker claims one due pipeline in tx1 (`FOR UPDATE
> SKIP LOCKED` + fresh UUID fencing token + lease), runs the external call outside any
> transaction (`StepRunner`), then reports the result under the verified ownership lock in tx2
> (`StepReporter`). A restart resumes from the rows; a crashed worker's pipeline is reclaimed
> after lease expiry.

## Scope — domain model (ADR-016) and execution layer (ADR-021)

This module implements **both halves** of the install/delete pipeline design: the **durable
domain model** (ADR-016) — state, data model, uniqueness rule, failure semantics, and lifecycle —
and the **claim-pull execution layer** (ADR-021) — when, how often, and with what concurrency
pipelines are advanced. The execution layer includes the claim-pull worker and scheduler
(`PipelineWorker`, `PipelineScheduler`), the claim mechanism (`PipelineClaimer`, `FOR UPDATE SKIP
LOCKED` + UUID fencing token + lease), the two-transaction split (tx1 claim → external call
outside any tx → tx2 guarded write-back), the per-call timeout decorator
(`TimeBoundedInfraManagerClient`), and the two-case cancel (`PipelineControl` Case A idle /
Case B cooperative). Both [ADR-016](docs/adr/016-install-delete-pipeline-domain-model.md) and
[ADR-021](docs/adr/021-pipeline-execution-model.md) are implemented here.

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
├── model/       Domain value/contract types (TaskType, TaskProgress, Recipe)
├── service/     @Component/@Service beans only: creation, cancel, and the claim-pull execution engine (PipelineWorker/PipelineClaimer/StepRunner/StepReporter/PipelineScheduler)
├── client/      InfraManager boundary (InfraManagerClient) + its exception contract
├── repository/  Spring Data repositories (guarded-CAS transitions)
├── controller/  GlobalAdvice (REST exception handler) — the REST layer added later
└── utils/       Static helpers (TaskSettings — effective per-task setting resolution + deadlines)
```

Each task's behaviour is a **`TaskType`** (`model/`): `TerraformTask` and `ConditionCheckTask` (in
`service/`) implement the `execute` → `check` lifecycle (and may override the default no-op `postCheck`),
and `StepRunner` resolves a task row to its type **by name** through `TaskTypeRegistry` (an unknown name produces `StepOutcome.unknownTask()`, handled by `TaskMachine.failUnknownTask`) — so a new kind of task is a new self-registering implementation, not an edit to a
`switch`. An unknown name fails the task with `ErrorCode.UNKNOWN_TASK`.

## Documentation

- [docs/adr/016-…](docs/adr/016-install-delete-pipeline-domain-model.md) — the domain model
- [docs/adr/021-…](docs/adr/021-pipeline-execution-model.md) — the execution model
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
