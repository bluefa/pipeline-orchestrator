# ADR-016: Install/Delete Pipeline — Durable State-Machine Domain Model

## Status

Proposed — 2026-06-27 (revised 2026-07-01: `CONDITION_CHECK` bounded by a retry count, not `ttl`;
revised 2026-07-03: introduce the `PENDING` start-delay wait state, `PENDING → RUNNING` at claim — LIN-30;
revised 2026-07-03 (alignment pass): schema/enum catalog matched to the implementation —
`active_target` uniqueness column, actual `pipeline`/`task`/`task_check` columns, the third
observation table `terraform_result`, `PipelineType.CUSTOM`, `ErrorCode.UNKNOWN_TASK`;
revised 2026-07-04 (alignment pass 2, codex round 1): completion reads only `task_attempt`
(never `task_check`); `task_check` is 0..1 per attempt; `fail_count == attempt_number` holds only
on the retryable path; `terraform_result` written in the run phase outside tx2; constraint names
and column order matched to entities; `recipe_definition` write-once claim corrected).

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

Scale: ~2,000 targets; 8 catalog pipeline shapes (4 providers × install/delete — the
`RecipeDefinition` catalog), plus operator-composed `CUSTOM` runs. Terraform jobs run for
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

(Two edges the linear diagram elides: `startDelay == 0` enters `RUNNING` directly, skipping
`PENDING` — the fast path; and a `PENDING` pipeline can go straight to `CANCELLED` without ever
passing through `RUNNING` — an idle-cancel, ADR-021 Decision 6 Case A.)

**Why `PENDING` exists.** A pipeline may be created with a **start delay** — a grace period before
it should begin. The delay is a **creation-time input sourced from server configuration**
(`pipeline.start-delay`, a deployment default such as 15 s, applied by the creator when it seeds
`next_due_at`); this ADR does **not** model it as an API-mutable per-request field, and a
configured delay of zero means "start now" (the `startDelay == 0` fast path below). Before this
state a start-delayed pipeline was created directly as `RUNNING` with
`next_due_at = now + startDelay`, so throughout the delay window it *looked* like it was executing
when it was really only waiting — an operator dashboard could not tell "delay-waiting" from
"actively running." `PENDING` makes that wait a **first-class, filterable state**.

`PENDING` is the **start-delay wait state** and exists **only when a start delay is configured**.
A pipeline created with `startDelay > 0` enters `PENDING` with `next_due_at = now + startDelay`;
one created with `startDelay == 0` enters `RUNNING` directly (the fast path, saving a transition).
`PENDING → RUNNING` happens **at claim** — the delay having elapsed (`next_due_at <= now`), the
worker that claims the row transitions it to `RUNNING` in the **same claim transaction** before
dispatching the first task, so the claim holder stays the only writer of `status` (the mechanism
and its rationale are ADR-021 Decision 2). `PENDING` is **non-terminal**: it is subject to the
per-target uniqueness rule (Decision 4) and is cancelled immediately (no live claim exists yet)
rather than terminalized by a worker.

The **current task** is the lowest-`sequence` `READY`/`IN_PROGRESS` task; tasks ahead of it are
explicitly `BLOCKED` until their predecessor reaches `DONE` (a task is created BLOCKED and
flips to READY; the first task starts READY). A `PENDING` pipeline already owns its full task
chain (created with the pipeline); those tasks begin executing once it transitions to `RUNNING`.
Pipeline status is a stored projection: each **task-driven** change is written in the same
transaction as the task transition that causes it, and the one **non-task-driven** change —
`PENDING → RUNNING` — is written by the claiming worker in its claim transaction (ADR-021
Decision 2), atomically with the lease stamp and without any task transition. A terminal
transition also clears the `active_target` uniqueness slot in that same transaction
(Decision 4, **Schema**). Either way a scan can filter on `status` cheaply.

Four core enums (`TaskStatus`, `PipelineStatus`, `PipelineType`, `ErrorCode`), plus the closed
`TaskOperation` set. The task *kind* (TERRAFORM_JOB, CONDITION_CHECK) is deliberately **not** an
enum: it is the **mechanism** — an open set of `TaskType` executor names, registry-validated at
boot and persisted as `task_name`. A task's `task_definition` (a `TaskDefinition` catalog
constant name, stored as a version-frozen string) is the row's source of truth; `task_name` and
`operation` are write-once projections derived from it — the mechanism selects the executor, the
operation the domain action within it. A pipeline's recipe (its ordered task list) is a code
default per `(type, provider)`; a `CUSTOM` pipeline instead takes its task list from the
operator's request, validated against the `TaskDefinition` catalog (no persisted recipe).

### 3. Observation is separate from state

Three **observation tables** — `task_attempt` (per-retry-attempt outcome), `task_check`
(per-attempt poll summary), and `terraform_result` (per-job Terraform log, recorded when a
`TERRAFORM_JOB` attempt reaches its verdict) — carry what an operator needs to first-diagnose a failure: the raw external
`response` per attempt (a TF dispatch, or a condition's check payload), the final outcome, whether the condition's terminal poll was not-met (`CONDITION_NOT_MET`) or a check error (`CHECK_ERROR`/`CALL_TIMEOUT`), with the per-cause split in `task_check`, poll counts, the last external response. They also hold the **result the completion
`check(attempt, task)` reads** to decide a task is done — the reconciler reads only the *latest*
attempt row, and only for that; claim, scheduling, and pipeline transitions never read them.
Losing a row never corrupts state: for a `TERRAFORM_JOB` a missing latest result falls through to
`executionTimeout` and re-dispatches (idempotent); a `CONDITION_CHECK` has no async result to lose —
a poll lost to a crash is reclaimed on lease expiry (ADR-021 Decision 5) and re-polled — either way the cost is a delay, not correctness
(the three invariants are in the **Schema** section). They add no domain column and no enum.

### 4. One active pipeline per target

A uniqueness rule allows only one non-terminal pipeline per target (`PENDING` counts as
non-terminal, so a start-delayed pipeline holds the slot the instant it is created — the
`active_target` slot column is stamped at insert; see **Schema**). A duplicate
create — of any type — is **rejected with `409 Conflict`** (code `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`, "already
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
the duplicate-*create* response. Every dispatch is idempotent: a duplicate submit still leaves the
infrastructure correct ("already in the desired state" counts as success). This lets the execution model be
**at-least-once** and still correct — a crash between "InfraManager started the job" and "we
recorded the attempt result" is healed by re-dispatch — and lets the state machine drop a
`DISPATCHING` state. InfraManager does not de-duplicate (Constraint 1), so a re-dispatch may
create *harmless duplicate* jobs. A single `TERRAFORM_JOB` dispatch produces a set of **`N` job
ids**; the attempt's raw `response` (which carries those job ids) is recorded in `task_attempt`,
and task completion is a **code-level check** over that result — `check(attempt, task) → done?`,
each `TaskType` deserializing its own `response` — not a domain job column. For a `TERRAFORM_JOB`, if the result is lost,
the task does not stall: the per-task
`executionTimeout` fires and the task re-dispatches as a fresh run (idempotent), so correctness
never depends on retaining the job ids. We never "reclaim" prior jobs; `(task_id, attempt_number)` is
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
  rows + the three observation tables. Worker-outage and queue-wait alerts are deferred.
- Per-target uniqueness rejects a concurrent INSTALL and DELETE for the same target by
  construction — intended, not a limitation.

## Schema

**Domain state tables**

- `pipeline(id, type, target, cloud_provider, recipe_definition, status, created_at,
  last_activity_at, active_target)` — execution adds `next_due_at, claimed_by, claimed_until,
  cancel_requested` (see ADR-021). `cloud_provider` is a **write-once** (`updatable=false`) cache
  of the provider looked up at create for recipe selection; `recipe_definition` is stamped at
  create with the `RecipeDefinition` constant name the Admin API joins on (set once at insert,
  though not annotation-immutable). **Per-target uniqueness (Decision 4) is enforced by
  `active_target`**: MySQL 8 — the target engine — has no partial (filtered) unique index, so
  instead of a `WHERE status non-terminal` index the application keeps `active_target = target`
  while the pipeline is non-terminal and sets it `NULL` in the same transaction as the terminal
  transition (tx2 convergence, or the idle-cancel — ADR-021 Decisions 4/6); a plain `UNIQUE`
  constraint on that column (`uq_pipeline_active_target`) carries the invariant (MySQL permits
  multiple `NULL`s, so terminal rows never collide). A concurrent duplicate create loses the
  insert race and surfaces as `409 Conflict` (`ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`; the trigger
  path matches that constraint name to translate the violation).
- `task(id, pipeline_id, sequence, task_name, operation, task_definition,
  consumes_terraform_slot, description, status, fail_count, error_code, started_at, ready_at,
  finished_at, next_check_at, polling_interval, execution_timeout, max_fail_count, version)` —
  `(pipeline_id, sequence)` is unique (`uq_task_pipeline_sequence`). `task_definition` is the
  row's source of truth (a version-frozen `TaskDefinition` constant name); `task_name` (the
  executor mechanism) and `operation` are write-once caches derived from it, as is the
  `consumes_terraform_slot` slot-gate flag (ADR-021 Decision 7; a supporting index
  `idx_task_slot_status` on `(consumes_terraform_slot, status)` backs the slot count).
  `description` is the operator's per-step note on a `CUSTOM` run. `version` is an optimistic
  lock kept as defense-in-depth under the pipeline-row `FOR UPDATE` serialization (ADR-021
  Decision 4). No job-id column: one dispatch's `N` job ids live inside the `task_attempt`
  `response`, and completion is a code-level `check(attempt, task)` over the latest attempt result.

**Observation tables** (per attempt; only the *latest* `task_attempt` row is read — by the
completion `check` — nothing else; `task_check` and `terraform_result` are write-only)

- `task_attempt(id, task_id, attempt_number, response, status, error_code, started_at, finished_at)`
  — one row per retry attempt; `attempt_number` is assigned at creation as the pre-attempt
  `fail_count + 1`. On a **retryable** failure the attempt is closed `FAILED` *and* `fail_count`
  is incremented, so a committed retryable-failed attempt has `fail_count == attempt_number` (this
  covers the common paths — a job-level `JOB_FAILED`, an `EXECUTION_TIMEOUT`, a call failure
  including an **empty dispatch response**, which surfaces as a retryable `CHECK_ERROR`). A task
  **failed outright** — non-retryable — instead closes its attempt `FAILED` **without** incrementing
  `fail_count`, so that equality does not hold there. The outright paths are: a **stored** dispatch
  response that is malformed or carries no usable job ids, which the check phase fails as a terminal
  `CHECK_ERROR`; and an unresolved definition → `UNKNOWN_TASK` (which, on a not-yet-dispatched task,
  fails it with no attempt row at all).
  `response` (text) holds the raw external
  response — for a `TERRAFORM_JOB` the **set** of `N` job ids (one dispatch → `N` jobs), for a
  `CONDITION_CHECK` the check payload; each `TaskType` deserializes its own `response`. The latest attempt
  row is the input to the completion `check`.
- `task_check(id, task_attempt_id, call_count, not_met_count, api_error_count,
  call_timeout_count, last_external_status, last_checked_at)` — at most one row per attempt
  (1:0..1): the row is created lazily on the **first observed in-progress/errored poll**, so a
  `TERRAFORM_JOB` attempt that reaches its verdict on the very first `check` (no `RUNNING` or
  errored poll recorded) has **no** `task_check` row at all. When an attempt does poll job status
  across turns its single row is UPDATEd in place (`call_count > 1`), so rows grow with attempts,
  not polls, the not-met/error counts accumulating within that one row. A `CONDITION_CHECK` poll
  **is** one attempt (`call_count = 1`), so a row is inserted per poll (bounded by `maxFailCount`)
  and its count columns are degenerate (0/1, mirroring the attempt's outcome); the row is kept for
  **one schema and one completion-check code path across both kinds**, carrying the poll's typed
  outcome (`last_external_status`) while the raw payload stays in `task_attempt.response`. The
  not-met-vs-error breakdown for a condition is therefore a **cross-row diagnostic aggregate**
  (summing the per-poll rows), distinct from the completion `check`, which reads only the latest
  `task_attempt` row and never `task_check` (invariant #1).

- `terraform_result(id, task_id, attempt_number, job_id, succeeded, result_path, result,
  truncated, created_at)` — the post-completion (postCheck) observation: when a `TERRAFORM_JOB`
  attempt reaches its verdict, one row is written per job observed finished in that turn,
  carrying the Terraform log (`result`; tail-first truncated past 16 MB with `truncated` set —
  failure causes cluster at the end of a log). A job whose log fetch fails leaves a pointer row
  (`result = NULL`) with `result_path` as the trail to the full text. Unlike the other two
  tables, it is written in the **run phase — outside the guarded tx2** (each `save` self-commits
  before the write-back's `claimed_by` guard runs), so a stale straggler whose tx2 later no-ops
  may still have written a diagnostic row; that is harmless, and the
  `(task_id, attempt_number, job_id)` unique key (`uq_terraform_result_attempt_job`) makes the
  re-run (crash / lease-expiry reclaim) idempotent. Write-only like `task_check`: the engine never reads it
  (design: [terraform-client-and-postcheck-design.md](../terraform-client-and-postcheck-design.md) §4.4).

Relationships: `pipeline 1:N task 1:N task_attempt 1:0..1 task_check`;
`task_attempt 1:0..N terraform_result` (one row per finished job of that attempt, keyed
`(task_id, attempt_number, job_id)`).

**Observation invariants**

1. The reconciler reads **only the latest `task_attempt` row**, and only to evaluate task
   completion (`check(target, task, attempt)`); it **never reads `task_check` or
   `terraform_result`** (both are write-only diagnostics). Claim, scheduling, and pipeline
   transitions depend only on `pipeline`/`task`.
2. `task_check` is **at most one row per attempt** (0..1): a `TERRAFORM_JOB` attempt UPDATEs it in
   place across polls (and writes none if it completes on its first `check`); a `CONDITION_CHECK`
   inserts one per poll, bounded by `maxFailCount`. `terraform_result` is one row per
   `(attempt, job)`, deduplicated by its unique key. No RLE, no pruner.
3. Losing an observation row never corrupts state: for a `TERRAFORM_JOB` a missing latest result
   makes `check` fall through to the per-task `executionTimeout`, which re-dispatches a fresh run
   (idempotent); a `CONDITION_CHECK` has no async result to lose — a lost poll is reclaimed on lease
   expiry (Decision 5, ADR-021) and re-polled. A lost `terraform_result` row is a lost diagnostic
   (the log), never lost state. The cost of loss is by kind — a TF re-dispatch (delay + harmless duplicate jobs) or a condition's delayed re-poll — never incorrectness.

**Enums** — the four core enums list their full canonical value set below; `TaskOperation` is a
large closed set enumerated in code (`TaskOperation.java`), described rather than listed here.

| enum | values |
|---|---|
| `TaskStatus` | BLOCKED, READY, IN_PROGRESS, DONE, FAILED, CANCELLED |
| `PipelineStatus` | PENDING, RUNNING, DONE, FAILED, CANCELLED (`PENDING` and `RUNNING` are the two non-terminal values) |
| `PipelineType` | INSTALL, DELETE, CUSTOM |
| `ErrorCode` | JOB_FAILED, EXECUTION_TIMEOUT, CONDITION_NOT_MET, CHECK_ERROR, CALL_TIMEOUT, UNKNOWN_TASK |
| `TaskOperation` | closed set of 25 — 24 `TERRAFORM_JOB` operations (8 execution units × PLAN/APPLY/DESTROY) + the `NETWORK_READY` condition check; each value owns the mechanism that executes it. Full list in `TaskOperation.java`. |

The task *kind* (TERRAFORM_JOB, CONDITION_CHECK) is deliberately not an enum: it is the open
mechanism / `TaskType`-name set, registry-validated at boot and persisted as `task_name` (§2).
`UNKNOWN_TASK` is the degradation code for a stored task whose definition no longer resolves.
Catalog enums (`TaskDefinition`, `RecipeDefinition`) persist by constant **name** (string), so a
removed or renamed value degrades cleanly instead of breaking reads.

## Links

- [ADR-021](021-pipeline-execution-model.md) — the execution model that drives this state machine
- [adr-016-history.md](../../design/pipeline/adr-016-history.md) — design history & rationale (maximal → minimal, revisions)
- Related: ADR-006 (confirmation model), ADR-009 (process status). A pipeline runs between CONFIRMED and INSTALLED.

## Glossary

- **InfraManager** — runs Terraform jobs (async; one dispatch returns a set of `N` job ids; a worker pod runs each apply).
- **BackendManager** — the integration/approval and target-source service.
- **Terraform job** — one infrastructure apply; runs for minutes.
- **Current task** — the lowest-`sequence` `READY`/`IN_PROGRESS` task of a pipeline that is executing,
  i.e. one that has reached `RUNNING`. A `PENDING` pipeline already holds its full task chain but
  runs none of it until it transitions to `RUNNING` at claim.
- **PENDING pipeline** — a start-delayed pipeline not yet first-claimed: before `next_due_at` it
  waits on the start-delay timer, and once due it is claim-eligible (it may briefly sit unclaimed
  under cap/worker pressure) until a worker transitions it to `RUNNING` in the claim (ADR-021
  Decision 2). A non-terminal state with no live claim, so it is cancelled immediately (ADR-021
  Decision 6, Case A) since no worker owns it.
