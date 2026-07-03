# ADR-016: Install/Delete Pipeline — Durable State-Machine Domain Model

## Status

Proposed — 2026-06-27 (revised 2026-07-01: `CONDITION_CHECK` bounded by a retry count, not `ttl`).

The **domain half** of the install/delete pipeline design: the durable state, data model,
uniqueness rule, failure semantics, and lifecycle. The **execution model** — how the state
machine is driven forward (runner, worker pool, concurrency, crash recovery) — is a separate,
independently-revisable decision in [ADR-021](021-pipeline-execution-model.md), so the
execution strategy can change without re-opening the domain model.

## Context

Today, installing or deleting a customer's infrastructure means an operator manually runs
Terraform jobs through **InfraManager** from a browser session. We want this automated: started
from the Admin console, then carried to completion on its own — surviving restarts — with a
visible run history.

- **Admin console** creates a pipeline, then closes; no browser stays open.
- **The orchestrator** drives the pipeline forward (its runtime is [ADR-021](021-pipeline-execution-model.md)).
- **InfraManager** runs Terraform jobs asynchronously: one dispatch returns a **set of `N` job
  ids**, and a Kubernetes worker pod runs each apply later.
- **BackendManager** owns integration/approval and target-source data.

Scale: ~2,000 targets; ~12 pipeline shapes (provider × install/delete). Terraform jobs run for
**minutes**; condition checks are fast readiness probes, bounded by a retry count rather than a long wall-clock wait.

Constraints:

1. InfraManager has **no de-duplication** — the same job submitted twice runs twice — but every
   execution API is **idempotent**, so the infrastructure result is unharmed.
2. Results can be lost (rare worker failure); we do not distinguish "still running" from "lost"
   — for a `TERRAFORM_JOB` an execution timeout absorbs both (a condition has no async result to
   lose; a lost poll is reclaimed on lease expiry and re-polled).

## Decision

### 1. The database is the only state

The pipeline's state lives in database rows; there is no in-memory authority to lose. Whatever
runs the pipeline (ADR-021) is stateless with respect to progress — it reads the rows and
resumes. Every decision below depends on this.

### 2. Two domain tables, a small durable state machine

`pipeline` and `task` are the **domain state tables** (full schema in the **Schema** section below).

```
Task:      BLOCKED ──▶ READY ──▶ IN_PROGRESS ──▶ DONE | FAILED | CANCELLED
Pipeline:  PENDING ──▶ RUNNING ─────────────▶ DONE | FAILED | CANCELLED
```

`PENDING` is the start-delay wait state (LIN-30): a pipeline created with `start-delay > 0` begins
`PENDING` and flips to `RUNNING` at its first claim; a zero-delay pipeline starts `RUNNING` directly.
Both `PENDING` and `RUNNING` are non-terminal.

The **current task** is the lowest-`seq` `READY`/`IN_PROGRESS` task; tasks ahead of it are
explicitly `BLOCKED` until their predecessor reaches `DONE` (a task is created BLOCKED and
flips to READY; the first task starts READY). Pipeline status is a stored projection, written
in the same transaction as the state change that sets it — the claim that flips `PENDING`→`RUNNING`,
or the task transition that terminalizes the pipeline — so a scan can filter on it cheaply.

Five core enums (`TaskStatus`, `PipelineStatus`, `TaskKind`, `PipelineType`, `ErrorCode`), plus
a conditional `TaskOperation` when the operation set is closed (an open set is registry-validated).
A task's `kind` selects the executor; its `operation` selects the domain action within it. A
pipeline's recipe (its ordered task list) is a code default per `(type, provider)`.

### 3. Observation is separate from state

Two **observation tables** — `task_attempt` (per-retry-attempt outcome) and `task_check`
(per-attempt poll summary) — carry what an operator needs to first-diagnose a failure: the raw external
`response` per attempt (a TF dispatch, or a condition's check payload), the final outcome, whether the condition's terminal poll was not-met (`CONDITION_NOT_MET`) or a check error (`CHECK_ERROR`/`CALL_TIMEOUT`), with the per-cause split in `task_check`, poll counts, the last external response. They also hold the **result the completion
`check(attempt, task)` reads** to decide a task is done — the reconciler reads only the *latest*
attempt row, and only for that; claim, scheduling, and pipeline transitions never read them.
Losing a row never corrupts state: for a `TERRAFORM_JOB` a missing latest result falls through to
`executionTimeout` and re-dispatches (idempotent); a `CONDITION_CHECK` has no async result to lose —
a poll lost to a crash is reclaimed on lease expiry (ADR-021 Decision 5) and re-polled — either way the cost is a delay, not correctness
(the three invariants are in the **Schema** section). They add no domain column and no enum.

### 4. One active pipeline per target

A uniqueness rule allows only one non-terminal pipeline per target. A duplicate create — of any
type — is **rejected with `409 Conflict`** (code `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`, "already
an active run for this target") rather than returning the existing run; the trigger endpoint must
honor this **contract**. The trigger is a human call — an operator pressing "try" in the web admin,
not a machine's at-least-once redelivery — so a duplicate (double-click, re-click after a timeout)
is most honestly shown as "already running": a person reads and understands a 409, and needs no
idempotent reinterpretation of a silent no-op. What this 409 contract enforces is the per-target
uniqueness rule — at most one non-terminal pipeline per target — which holds independent of the
response shape. ADR-021's single-owner-per-pipeline invariant is a separate concern: it rests on
that ADR's own claim + lease mechanism, not on the create response. The orchestrator and scheduler
never call create — they only claim an existing pipeline — so this rejection affects the admin
trigger path alone and leaves ADR-021 untouched.

### 5. Correctness rests on idempotency, not exactly-once

The idempotency this section relies on is about **dispatch to InfraManager** (a re-dispatch does
not harm the infrastructure) and is unchanged by Decision 4's trigger contract, which governs only
the duplicate-*create* response. Every dispatch is idempotent: a duplicate submit still leaves the infrastructure correct
("already in the desired state" counts as success). This lets the execution model be
**at-least-once** and still correct — a crash between "InfraManager started the job" and "we
recorded the attempt result" is healed by re-dispatch — and lets the state machine drop a
`DISPATCHING` state. InfraManager does not de-duplicate (Constraint 1), so a re-dispatch may
create *harmless duplicate* jobs. A single `TERRAFORM_JOB` dispatch produces a set of **`N` job
ids**; the attempt's raw `response` (which carries those job ids) is recorded in `task_attempt`,
and task completion is a **code-level check** over that result — `check(attempt, task) → done?`,
each `TaskKind` deserializing its own `response` — not a domain job column. For a `TERRAFORM_JOB`, if the result is lost,
the task does not stall: the per-task
`executionTimeout` fires and the task re-dispatches as a fresh run (idempotent), so correctness
never depends on retaining the job ids. We never "reclaim" prior jobs; `(task_id, attempt_no)` is
a logical attempt identity, not an InfraManager key.

### 6. Bounded waiting and retry

- `fail_count` per task. A failed dispatch or poll increments it; below `maxFailCount` the task
  re-runs as a **fresh run** (completed work is a no-op — Terraform converges), at or above it
  the task is `FAILED`. For a `CONDITION_CHECK` a **not-met poll is a failed poll**: it increments
  `fail_count` and re-checks after `polling_interval`. Not-met polls and check errors
  (`CHECK_ERROR`/`CALL_TIMEOUT`) share this one budget; the poll that pushes `fail_count` to
  `maxFailCount` fails the task, and its cause sets the **terminal `error_code`**
  (`CONDITION_NOT_MET` when that last poll was still not-met) — the authoritative per-cause
  breakdown lives in `task_check`, not in the single `error_code`. Each poll's own `task_attempt.error_code` records that poll's cause, so the task's terminal `error_code` is the last attempt's. A condition is thus a fast
  probe bounded by a **retry count**, not a wall-clock deadline: the first poll is immediate and
  each retry waits `polling_interval`, so retry spacing totals about `(maxFailCount − 1) ×
  polling_interval`, with total elapsed also including each poll's call/queue time (slow checks or
  call timeouts lengthen it; the not-met/error mix changes only how many not-met samples occur). Size `polling_interval` to the condition's cadence and keep `maxFailCount` modest
  (each poll writes one attempt/observation row; `max_fail_count ≥ 1`, so at least one poll runs).
- One **per-task** deadline: `executionTimeout`, for `TERRAFORM_JOB` only (a condition has no
  long-running job to time out); with the **per-call** timeout, both map to canonical `ErrorCode`
  values, not separate states.
- No circuit breaker — a systemic failure is delay (timeout + retry + alert), not corruption.

### 7. Minimal lifecycle

Two task kinds (`TERRAFORM_JOB`, `CONDITION_CHECK`). **Retry is a fresh run.** **Cancel**
converges directly to `CANCELLED` — there is no `CANCELLING` state — and terminalizes every
non-terminal task (`BLOCKED`/`READY`/`IN_PROGRESS` → `CANCELLED`); a `FAILED` pipeline marks the
failing task `FAILED` and the rest `CANCELLED`. A terminal state is never resurrected. *How*
cancel is applied against a live worker is an execution concern (ADR-021).

## Considered Options

| Option | Verdict | Why |
|---|---|---|
| **A. Durable DB state machine — two domain tables, status is the row** | **Chosen** | The row *is* the state; restart-safe; idempotency makes at-least-once execution correct. |
| B. Maximal model (observation ledger, attempt log, event outbox, snapshots; 6 tables) | Rejected | Far too large for "run an ordered chain of two task kinds for one target" (see history). |
| C. One row with an embedded JSON task list | Rejected | Loses per-task query/index (current task, due scan, retry counts); a child `task` table is cheaper. |

## Consequences

**Good**

- Current state is one rule: the row. Self-heals across crashes and redeploys via idempotent
  re-dispatch — no exactly-once machinery.
- Small and stable: two domain tables, five core enums, two task kinds. The model is unchanged
  when the execution strategy (ADR-021) changes.

**Costs we accept**

- No full per-call audit ledger or event outbox. Audit = logs/metrics + the `pipeline`/`task`
  rows + the two observation tables. Worker-outage and queue-wait alerts are deferred.
- Per-target uniqueness rejects a concurrent INSTALL and DELETE for the same target by
  construction — intended, not a limitation.

## Schema

**Domain state tables**

- `pipeline(id, type, target, status, created_at, last_activity_at)` — execution adds
  `next_due_at, claimed_by, claimed_until, cancel_requested` (see ADR-021). A **partial unique
  index on `target` over non-terminal `status`** enforces Decision 4's per-target uniqueness; a
  concurrent duplicate create loses the insert race and surfaces as `409 Conflict`
  (`ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`).
- `task(id, pipeline_id, seq, kind, operation, status, fail_count, error_code,
  started_at, ready_at, finished_at, next_check_at, polling_interval, execution_timeout,
  max_fail_count)` — no job-id column: one dispatch's `N` job ids live inside the `task_attempt`
  `response`, and completion is a code-level `check(attempt, task)` over the latest attempt result.

**Observation tables** (per attempt; only the *latest* row is read — by the completion `check` — nothing else)

- `task_attempt(id, task_id, attempt_no, response, status, error_code, started_at, finished_at)`
  — one row per retry attempt; `attempt_no` is assigned at creation as the pre-attempt
  `fail_count + 1`, so once a failed attempt commits `task.fail_count` equals its `attempt_no`.
  `response` (text) holds the raw external
  response — for a `TERRAFORM_JOB` the **set** of `N` job ids (one dispatch → `N` jobs), for a
  `CONDITION_CHECK` the check payload; each `TaskKind` deserializes its own `response`. The latest attempt
  row is the input to the completion `check`.
- `task_check(id, task_attempt_id, call_count, not_met_count, api_error_count,
  call_timeout_count, last_external_status, last_response_code, last_response_summary,
  last_checked_at)` — at most one row per attempt (1:0..1). A `TERRAFORM_JOB` attempt polls job
  status many times, so its row is UPDATEd in place (typically `call_count > 1`) and rows grow with attempts,
  not polls, the not-met/error counts accumulating within that one row. A `CONDITION_CHECK` poll
  **is** one attempt (`call_count = 1`), so a row is inserted per poll (bounded by `maxFailCount`)
  and its count columns are degenerate (0/1, mirroring the attempt's outcome); the row is kept for
  **one schema and one completion-check code path across both kinds**, carrying the poll's typed
  outcome (`last_external_status`) while the raw payload stays in `task_attempt.response`. The
  not-met-vs-error breakdown for a condition is therefore a **cross-row diagnostic aggregate**
  (summing the per-poll rows), distinct from the completion `check`, which reads only the latest
  row (invariant #1).

Relationships: `pipeline 1:N task 1:N task_attempt 1:0..1 task_check`.

**Observation invariants**

1. The reconciler reads **only the latest `task_attempt`/`task_check` row**, and only to evaluate
   task completion (`check(attempt, task)`); claim, scheduling, and pipeline transitions depend
   only on `pipeline`/`task`.
2. `task_check` is one row per attempt: a `TERRAFORM_JOB` attempt UPDATEs it in place (no per-poll
   inserts); a `CONDITION_CHECK` inserts one per poll, bounded by `maxFailCount`. No RLE, no pruner.
3. Losing an observation row never corrupts state: for a `TERRAFORM_JOB` a missing latest result
   makes `check` fall through to the per-task `executionTimeout`, which re-dispatches a fresh run
   (idempotent); a `CONDITION_CHECK` has no async result to lose — a lost poll is reclaimed on lease
   expiry (Decision 5, ADR-021) and re-polled. The cost of loss is by kind — a TF re-dispatch (delay + harmless duplicate jobs) or a condition's delayed re-poll — never incorrectness.

**Enums** (canonical values)

| enum | values |
|---|---|
| `TaskStatus` | BLOCKED, READY, IN_PROGRESS, DONE, FAILED, CANCELLED |
| `PipelineStatus` | PENDING, RUNNING, DONE, FAILED, CANCELLED |
| `TaskKind` | TERRAFORM_JOB, CONDITION_CHECK |
| `PipelineType` | INSTALL, DELETE |
| `ErrorCode` | JOB_FAILED, EXECUTION_TIMEOUT, CONDITION_NOT_MET, CHECK_ERROR, CALL_TIMEOUT |

`TaskOperation` is a conditional sixth enum, present only when the operation set is closed; an
open/configured set uses a registry instead.

## Links

- [ADR-021](021-pipeline-execution-model.md) — the execution model that drives this state machine
- [adr-016-history.md](../../design/pipeline/adr-016-history.md) — design history & rationale (maximal → minimal, revisions)
- Related: ADR-006 (confirmation model), ADR-009 (process status). A pipeline runs between CONFIRMED and INSTALLED.

## Glossary

- **InfraManager** — runs Terraform jobs (async; one dispatch returns a set of `N` job ids; a worker pod runs each apply).
- **BackendManager** — the integration/approval and target-source service.
- **Terraform job** — one infrastructure apply; runs for minutes.
- **Current task** — the lowest-`seq` `READY`/`IN_PROGRESS` task of a non-terminal (`PENDING`/`RUNNING`) pipeline.
