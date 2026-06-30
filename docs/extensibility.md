# Extensibility — v1 seams

This is a **v1** domain model. Three extensions are anticipated: more tasks, a Task Post-Check, and
an Event Outbox. This document records **where each plugs in** so the v1 code stays minimal (none of
them is built yet — YAGNI) while the seams are deliberate, not accidental.

The guiding principle: the extension points are **concrete and few** — the recipe catalog
(`recipe/Recipes.java`), the **`TaskType` registry** (`service/task/TaskTypeRegistry.java`), and the single
per-pipeline transaction boundary. Adding a feature is a new file or a new entry in a known place, not a
re-wiring.

## 1. More tasks

Two sub-cases:

- **More tasks of existing types** (a longer or different chain) — add an entry to
  `recipe/Recipes.java`. A recipe is just an ordered list of `(taskName, operation)` steps; the state
  machine runs any length. No schema change, no new state. The `BLOCKED → READY` unblocking already
  sequences an arbitrary chain.
- **A new *kind* of task** — implement the **`TaskType`** interface
  (`taskName()` / `execute(target, task)` / `check(target, task) → TaskProgress`) as a `@Component` in
  `service/task/type/`. It **registers itself** in `TaskTypeRegistry` (built from the injected `List<TaskType>`),
  so there is **no enum to extend and no `switch` to edit** — `TaskStateMachine` resolves the row's
  `taskName` to its type generically. Reference the new type's `taskName` from a recipe step. Its
  failure modes are new `ErrorCode` values returned as `TaskProgress.failed(reason, retryable)` and
  handled exactly like the existing ones (see [exception-strategy.md](exception-strategy.md)).
  `TaskStatus` is unchanged.

This is the seam the design deliberately opened: a third behaviour (alongside `TerraformTask` and
`ConditionCheckTask`) is a new file that self-registers, never a change to a closed type. A task row
whose stored `taskName` no longer resolves to a type fails with `ErrorCode.UNKNOWN_TASK`, so a
removed/renamed type degrades to a clean failure rather than a crash.

**Operations.** `TaskOperation` is a closed enum because the v1 set is closed (ADR-016 §2). When the
operation set becomes open or configured, replace the enum with a small registry (operation key →
metadata) and validate against it — the ADR names this as the alternative. Until then, a closed enum
keeps it type-safe and is the cheaper choice.

## 2. Task Post-Check

A post-check is a verification that runs *after* a task's main work succeeds (e.g. confirm the applied
infrastructure actually answers). The lifecycle is: **`execute` → `check` → `postCheck`**.

- **If the check is a distinct step**, it is already expressible today: add a `CONDITION_CHECK` step
  after the `TERRAFORM_JOB` step in the recipe. The `INSTALL` recipe already does exactly this
  (`apply-network` then `network-ready`). No new mechanism is needed for this common case.
- **If the check must be bound to the task itself** (one row, "done *and* verified"), override
  `TaskType.postCheck(target, task)`. This default-no-op method is already wired into `TaskStateMachine`:
  when `check` returns `Succeeded`, the engine calls `postCheck` and acts on its `TaskProgress` result
  before marking the task DONE. A `Succeeded` result completes the task; `Pending` reschedules it
  (identical to a poll reschedule); `Failed` retries or fails the task (same failure model as `check`).
  A failed post-check is returned as `TaskProgress.failed(...)` — a business value, never a thrown
  exception. The default `postCheck` returns `SUCCEEDED`, so all existing task types complete exactly
  as before: the seam is open but costs nothing until a type overrides it.

## 3. Event Outbox

An outbox durably publishes domain events (pipeline `DONE`/`FAILED`/`CANCELLED`, task transitions) to
downstream consumers with the same delivery guarantee as the state change.

- **The seam is the single per-pipeline transaction.** Every terminal/transition write already happens
  in one transaction: `PipelineEngine.converge` (via the guarded `finishIfRunning()` CAS),
  `PipelineControl.cancel`, and the `TaskStateMachine` transitions (all in `service/`). An outbox row is
  inserted **in that same transaction**, so the event is committed atomically with the state it
  describes — no dual-write gap.
- **What it adds:** a `pipeline_event` table (append-only) and a separate relay/poller that ships
  rows to consumers and marks them sent. The relay is independent of the engine — the engine's
  only job is the same-transaction insert.
- **What it must not do:** an enqueue failure is an *infrastructure* failure that rolls the transaction
  back, never a business `ErrorCode`. Event delivery is at-least-once; consumers dedupe.

ADR-016 explicitly defers the outbox (Consequences: "No … event outbox"), so it is out of scope for
v1; this section only fixes the insertion point so adding it later is a localized change.

## What is intentionally *not* abstracted

To keep the file count and concept count low (a stated goal of the ADR and the owner):

- No event/listener framework — the outbox is a table plus a relay, wired at the one transaction
  boundary above.
- No generic "pipeline definition" engine — recipes are code defaults in a `Map` (`Recipes`).
- `TaskOperation` stays a closed enum until the operation set actually opens.

## The two interfaces that earn their place

The file/concept budget is tight, so an `interface` must be justified — either a real external boundary
or genuine multi-implementation polymorphism. Exactly two qualify:

- **`InfraManagerClient`** (`client/`) — a real external integration, with a production (HTTP) and a
  test (fake) implementation.
- **`TaskType`** (`service/`) — a genuine strategy with two real implementations today
  (`TerraformTask`, `ConditionCheckTask`) and an open set tomorrow, resolved by name through a registry.
  A `switch` on a closed `kind` enum would force every new task type to edit `TaskStateMachine`; the
  interface + registry makes a new type an additive, self-registering file. A single-implementation
  interface would *not* earn its place — these two do.
