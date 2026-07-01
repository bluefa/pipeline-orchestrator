# Exception Handling Strategy

> Scope: the install/delete pipeline orchestrator (ADR-016 domain model). This document is the
> contract for *how failures are represented and handled*. It is enforced by code review (the
> spring-java21 skill §5.7, §6) and referenced from `AGENTS.md`.

## The one rule

**Business failures are values; only infrastructure failures are exceptions.**

A task that fails because the Terraform job failed, the condition never came true, or a deadline
passed is **not** an exceptional event — it is an expected outcome of running infrastructure work.
It is recorded as an `ErrorCode` on the task row and becomes part of the visible run history. It is
never thrown.

An exception is reserved for two things only:

1. **External-call failures** at the InfraManager boundary (timeouts, HTTP errors, connection
   failures, a null/malformed response). These are thrown by the `client` package (or by a `TaskType`'s
   own guard on a null response), **caught at exactly one boundary** (`StepRunner.runExternalCall`,
   phase-A, which runs OUTSIDE any transaction), and immediately translated into a `StepOutcome` value.
   No external exception escapes phase-A as an exception.
2. **Programmer errors / broken invariants** (a missing pipeline id, an interrupted thread, a
   misconfigured registry or settings). These fail fast and are not caught — they indicate a bug or an
   impossible state, not a business outcome.

The result: business logic in `TaskStateMachine`, `StepReporter`, and `PipelineControl` reads
as a straight-line state machine. Failure handling is not scattered through it as `try/catch`; it is
concentrated at the one external boundary (`StepRunner`) and expressed everywhere else as `ErrorCode`
/ `StepOutcome` data.

## The two kinds of failure, side by side

| | External-call failure | Business-rule failure |
|---|---|---|
| Examples | per-call timeout, HTTP 5xx/429, connection reset | job reported FAILED, condition never met within maxFailCount, execution timeout |
| Representation | **exception** (`CallTimeoutException` / `CallFailedException` — the client's closed failure vocabulary) | **value** (`ErrorCode` on the task row) |
| Thrown? | yes — by the `client` package | no — never thrown |
| Where handled | caught once in `StepRunner.runExternalCall` (phase-A, wrapping execute/check), translated to a `StepOutcome` | written directly to the row by `TaskStateMachine.applyOutcome` (tx2) |
| Lifetime | dies at the `StepRunner` (phase-A) boundary | durable; part of run history; drives retry-or-fail |
| Retryable? | yes (re-run is a fresh, idempotent attempt) | `JOB_FAILED`/`EXECUTION_TIMEOUT`/`CONDITION_NOT_MET` retry within `maxFailCount`, then fail |

The translation is deliberately small and total. Every external exception maps to one canonical
`ErrorCode`:

| Thrown at the boundary | Becomes |
|---|---|
| `CallTimeoutException` (per-call timeout exceeded) | `ErrorCode.CALL_TIMEOUT` |
| `CallFailedException` — any other failed call (HTTP error, connection failure, rejection, malformed/empty response), incl. a `TaskType` guard rejecting a null/blank job id or null status | `ErrorCode.CHECK_ERROR` |

The boundary (`StepRunner.runExternalCall`, the one helper wrapping execute/check) catches
**only** `CallTimeoutException` and `CallFailedException`. `CallInterruptedException` (a shutdown
interrupt) and any *other* `RuntimeException` (a genuine bug) are **not** caught — they propagate out of
`StepRunner` / `PipelineWorker.process` (fail-fast) rather than being mis-recorded as a business
`CHECK_ERROR`. See [exception-cases.md](exception-cases.md) §1.

Business outcomes never pass through that table — they are decided by the polling logic and written
straight to the row:

| Business outcome | `ErrorCode` |
|---|---|
| TERRAFORM poll reported the job FAILED | `JOB_FAILED` |
| TERRAFORM job ran past its execution timeout | `EXECUTION_TIMEOUT` |
| CONDITION not met on a poll (a failed poll; fails at `maxFailCount`) | `CONDITION_NOT_MET` |

## Where each layer sits

Under the ADR-021 claim-pull model one cycle is **two transactions with the external call in between**:
tx1 = `PipelineClaimer.claimOneDue` (claim + fencing token), then the external call in `StepRunner`
(phase-A, **NO transaction** — so no row lock is held across InfraManager), then tx2 = `StepReporter.report`
(guarded write-back). The single catch boundary lives in phase-A.

```
 client/     throws        ── external-call failures live here and only here
   InfraManagerClient          (interface; HTTP in prod, faked in tests). The production adapter,
                               driven by the ADR-021 runner, owns the per-call timeout and raises
                               CallTimeoutException / CallInterruptedException (exception/ package).
      │  (exception crosses exactly one boundary)
      ▼
 service/    catches+translates
   TaskType impls            call the client; a TaskType also guards a null/malformed response by
                             throwing, so it surfaces as an external-call failure (never an NPE)
   StepRunner (phase-A)      the single translation point (one `runExternalCall` helper wrapping
                             execute/check, OUTSIDE any tx): catch CallTimeoutException →
                             StepOutcome.callTimeout, catch CallFailedException → StepOutcome.callFailed.
                             CallInterruptedException and any non-CallException RuntimeException (a bug)
                             are NOT caught — they propagate (fail-fast) out of StepRunner /
                             PipelineWorker.process; business outcomes (TaskProgress) → StepOutcome value
   StepReporter (tx2)        no business try/catch — locks the pipeline row FOR UPDATE, verifies the
                             claim token, and maps the pre-computed StepOutcome to task transitions via
                             TaskStateMachine.applyOutcome (which itself has NO try/catch)

 PipelineScheduler.drain (the ADR-021 runner) catches RuntimeException PER PIPELINE around
                             worker.process: an infrastructure failure (e.g. an optimistic-lock clash)
                             or a bug is logged and that pipeline is skipped — it keeps its lease and is
                             reclaimed on expiry — never aborting the whole sweep. A CallInterruptedException
                             propagates as an interrupt that aborts the sweep (JVM shutdown).
```

`StepRunner` wraps **every** `TaskType` call — `execute` and `check` — in one `runExternalCall`
helper, the only `try/catch` in the business layer, and it does nothing but classify (returning a
`StepOutcome` value, never throwing):

```java
private StepOutcome runExternalCall(Task task, boolean dispatch, Supplier<StepOutcome> call) {
    try {
        return call.get();                        // the task type calls InfraManagerClient
    } catch (CallTimeoutException exception) {
        log.warn(...);
        return StepOutcome.callTimeout(dispatch); // external timeout → value (→ CALL_TIMEOUT in tx2)
    } catch (CallFailedException exception) {
        log.warn(...);
        return StepOutcome.callFailed(dispatch);  // external failure (incl. a null/malformed response) → value (→ CHECK_ERROR in tx2)
    }
}
// CallInterruptedException and any non-CallException RuntimeException are NOT caught — a shutdown interrupt
// and a genuine bug both propagate out of StepRunner / PipelineWorker.process (fail-fast), absorbed by
// PipelineScheduler.drain. A returned StepOutcome is the business value (dispatch/Succeeded/Pending/Failed/
// CallFailure); the check-phase observation (CALL_TIMEOUT/API_ERROR) is recorded later in tx2 by applyOutcome.
```

## The exceptions we *do* keep (and why)

These exception types survive, each for a precise reason — none is a business outcome.

1. **`CallTimeoutException`** and **`CallFailedException`** (top-level in
   `exception/`, the InfraManager transport-boundary vocabulary) — the client's closed failure vocabulary. `CallTimeoutException`
   signals that one InfraManager call exceeded the per-call timeout; `CallFailedException` signals any
   other failed call (HTTP error, connection failure, rejection, malformed/empty response — including a
   `TaskType` guard rejecting a null/blank job id or null status). The production adapter (driven by the
   ADR-021 runner, which owns the timeout) raises them, translating its transport exceptions into these
   types so it never leaks a raw `RuntimeException`. They exist so the boundary can distinguish a timeout
   (`CALL_TIMEOUT`) from any other call failure (`CHECK_ERROR`), and so it can catch exactly the
   external-call failures and let a genuine bug fail fast. Neither travels past `StepRunner` (phase-A).

2. **`DataIntegrityViolationException`** (Spring, caught in `service/PipelineCreator.java`) — the
   `active_target` unique violation when two creates race for one target. This is the one place an
   infrastructure exception is used as a **control signal**: the catch is targeted (only this type),
   and it compensates by returning the existing active run. This is how "one active pipeline per
   target" is enforced by the database rather than by application locking (ADR-016 §4).

3. **`OptimisticLockingFailureException`** (Spring/JPA, via `Task.@Version`) — under ADR-021 all task
   writes are serialized by the tx2 pipeline-row `FOR UPDATE` lock plus the claim-token check, so this is
   now **defense-in-depth** rather than the primary guard. A cancel race is resolved by that pipeline-row
   lock (the tx2 that holds it observes `cancel_requested` under the same lock and converges atomically);
   `@Version` only adds a backstop should a stale write ever slip past. If it does fire, it is **not
   caught in the business layer**; it propagates out of tx2 (`StepReporter.report`) and is absorbed by the
   **ADR-021 runner's** per-pipeline `catch (RuntimeException)` in `PipelineScheduler.drain` (out of this
   module's scope), which logs and skips that pipeline (it keeps its lease and is reclaimed on expiry).
   The terminal `CANCELLED` state is preserved; nothing is corrupted.

A fourth, **`CallInterruptedException`**, is the fail-fast guard: `StepRunner.runExternalCall`
catches only `CallTimeoutException`/`CallFailedException`, so an interrupt is **not caught** — it propagates
out of `StepRunner` / `PipelineWorker.process` as an interrupt that aborts the sweep (JVM shutdown) instead
of being recorded as a business `CHECK_ERROR`. Likewise `IllegalArgumentException` for a missing pipeline id
fails fast. Neither is a business outcome.

Note the one boundary that is a **business value, not an exception**: a task whose stored `taskName`
resolves to no registered `TaskType` is failed with `ErrorCode.UNKNOWN_TASK` — written to the row, never
thrown — so a removed/renamed task type degrades to a clean failure (ADR-016 §2).

## Why this shape

- **Failures are first-class data.** A failed task carries a queryable `ErrorCode` and timestamps;
  run history and alerting read the rows, not a log of stack traces.
- **Business logic stays linear.** The state machine is not threaded with error plumbing; the only
  `try/catch` is the boundary classifier.
- **At-least-once is safe.** Because a retry is a fresh, idempotent re-dispatch (ADR-016 §5), an
  external failure mid-flight costs a retry, not a corrupted state — so it never needs to be an
  exception that unwinds work.
- **Observation is separate.** What happened on each attempt (which job, how many polls, the last
  response) is recorded in the write-only `task_attempt` / `task_check` tables (ADR-016 §3). These are
  for diagnosis only; the engine never reads them, and losing them costs debuggability, never
  correctness. They **ride tx2** (`StepReporter` → `TaskStateMachine.applyOutcome` → `ObservationRecorder`)
  rather than a separate `REQUIRES_NEW` tx — the maximal async-observation split was rejected (ADR-016
  Option B), and at this scale only one task is serviced per cycle. A failed observation write rolls back
  and **retries the whole cycle** (the pipeline keeps its lease and is reclaimed on expiry), which cannot
  corrupt state (the task row is atomic), so the simplification keeps the ADR's
  correctness-only guarantee. `ObservationRecorder` is resilient to the common case (a missing attempt row is
  a no-op). If
  durable-under-failure observation is ever required, moving the writes to a `REQUIRES_NEW` boundary is
  the documented upgrade.

## How future features fit without changing the rule

- **More task kinds** — a new `TaskType` implementation (resolved by name, not a `switch`) brings its
  own `execute`/`check`. Its failure modes are new `ErrorCode` values returned as `TaskProgress.Failed`,
  still written as data, still translated from external exceptions at the same one boundary.
- **Event Outbox** — emitting an event on a terminal transition is a write in the *same transaction*
  as the state change. A failure to enqueue is an infrastructure failure (it rolls the transaction
  back), never a business `ErrorCode`.

See [extensibility.md](extensibility.md) for where these plug in.
