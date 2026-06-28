# pipeline-orchestrator

Durable install/delete **pipeline orchestrator** — the implementation of the
[ADR-016 domain model](docs/adr/016-install-delete-pipeline-domain-model.md).

A pipeline runs an ordered chain of tasks for **one target** to **INSTALL or DELETE**
infrastructure. Each task is a `TERRAFORM_JOB` (dispatch a Terraform job, poll until it
finishes) or a `CONDITION_CHECK` (poll until a condition is met). The pipeline survives
process restarts, never runs two active pipelines for the same target, retries a task a
few times before failing it, and can be cancelled.

> The **database row is the only state.** A periodic reconciler reads the rows and advances
> each running pipeline one step; a restart simply resumes from the rows.

## Stack

- Java 21, Spring Boot 3.3.5, Spring Data JPA
- **MySQL 8** in production; schema (tables, indexes, unique constraints) is generated from
  JPA entity annotations — no Flyway, no hand-written SQL migrations.
- Tests run on H2 in MySQL-compatibility mode (no Docker required).

## Layout

```
com.bff.pipeline
├── config/      Spring wiring: Clock + bounded call pool + typed settings
├── domain/      Entities (Pipeline, Task, TaskAttempt, TaskCheck) + 6 enums
├── repository/  Spring Data repositories (guarded-CAS transitions)
├── create/      Idempotent pipeline creation + per-(type) task recipes
├── control/     Admin cancel
├── im/          InfraManager boundary (the one real interface) + per-call timeout
└── reconcile/   The task state machine, the tick loop, and observation recording
```

## Documentation

- [docs/adr/016-install-delete-pipeline-domain-model.md](docs/adr/016-install-delete-pipeline-domain-model.md) — the domain model
- [docs/exception-strategy.md](docs/exception-strategy.md) — how external-call failures and
  business-rule failures are separated and handled
- [docs/extensibility.md](docs/extensibility.md) — the v1 seams for more task kinds, task
  post-checks, and an event outbox

## Build & test

```bash
mvn test       # runs on H2 (MySQL mode); no external services needed
mvn package    # builds the runnable jar
```
