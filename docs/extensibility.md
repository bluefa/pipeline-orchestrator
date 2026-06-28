# Extensibility — v1 seams

This is a **v1** domain model. Three extensions are anticipated: more tasks, a Task Post-Check, and
an Event Outbox. This document records **where each plugs in** so the v1 code stays minimal (none of
them is built yet — YAGNI) while the seams are deliberate, not accidental.

The guiding principle: the extension points are **concrete and few** — the recipe registry, the
`switch` on `TaskKind`, and the single per-pipeline transaction boundary. There are no speculative
plugin interfaces or strategy hierarchies; adding a feature edits a known place.

## 1. More tasks

Two sub-cases, both already supported by the shape of the code:

- **More tasks of existing kinds** (a longer or different chain) — add an entry to
  `create/Recipes.java`. A pipeline's recipe is just an ordered list of `(kind, operation)` steps;
  the state machine runs any length. No schema change, no new state. The `BLOCKED → READY` unblocking
  already sequences an arbitrary chain.
- **A new task kind** — add a value to `TaskKind` and one dispatch/poll branch in
  `reconcile/TaskMachine.java` (`dispatch` and `poll` switch on the kind). Its failure modes are new
  `ErrorCode` values, handled exactly like the existing ones (see
  [exception-strategy.md](exception-strategy.md)). `TaskStatus` is unchanged.

**Operations.** `TaskOperation` is a closed enum because the v1 set is closed (ADR-016 §2). When the
operation set becomes open or configured, replace the enum with a small registry (operation key →
metadata) and validate against it — the ADR names this as the alternative. Until then, a closed enum
keeps it type-safe and is the cheaper choice.

## 2. Task Post-Check

A post-check is a verification that runs *after* a task's main work succeeds (e.g. confirm the applied
infrastructure actually answers).

- **If the check is a distinct step**, it is already expressible today: add a `CONDITION_CHECK` step
  after the `TERRAFORM_JOB` step in the recipe. The `INSTALL` recipe already does exactly this
  (`apply-network` then `network-ready`). No new mechanism is needed for this common case.
- **If the check must be bound to the task itself** (one row, "done *and* verified"), the seam is
  `reconcile/TaskMachine.complete(Task)`: a post-check would run there before marking `DONE`, and a
  failed post-check would be a new `ErrorCode` written to the row — a business outcome, never a thrown
  exception. This keeps post-checks inside the same per-task transition and the same failure model.

## 3. Event Outbox

An outbox durably publishes domain events (pipeline `DONE`/`FAILED`/`CANCELLED`, task transitions) to
downstream consumers with the same delivery guarantee as the state change.

- **The seam is the single per-pipeline transaction.** Every terminal/transition write already happens
  in one transaction: `PipelineReconciliation.converge` (via the guarded `finish()` CAS),
  `PipelineControl.cancel`, and the `TaskMachine` transitions. An outbox row is inserted **in that same
  transaction**, so the event is committed atomically with the state it describes — no dual-write gap.
- **What it adds:** a `pipeline_event` table (append-only) and a separate relay/poller that ships
  rows to consumers and marks them sent. The relay is independent of the reconciler — the reconciler's
  only job is the same-transaction insert.
- **What it must not do:** an enqueue failure is an *infrastructure* failure that rolls the transaction
  back, never a business `ErrorCode`. Event delivery is at-least-once; consumers dedupe.

ADR-016 explicitly defers the outbox (Consequences: "No … event outbox"), so it is out of scope for
v1; this section only fixes the insertion point so adding it later is a localized change.

## What is intentionally *not* abstracted

To keep the file count and concept count low (a stated goal of the ADR and the owner):

- No `TaskExecutor`/`TaskHandler` interface per kind — two kinds are a `switch`, not a hierarchy.
  Introduce a strategy only if the kind count grows enough that the `switch` stops fitting on a screen.
- No event/listener framework — the outbox is a table plus a relay, wired at the one transaction
  boundary above.
- No generic "pipeline definition" engine — recipes are code defaults in a `Map`.

The boundary that *is* an interface — `ImClient` — earns it: it is a real external integration with a
production (HTTP) and a test (fake) implementation.
