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
   own guard on a null response), **caught at exactly one boundary** (`TaskMachine`), and immediately
   translated into an `ErrorCode`. No external exception escapes the engine's `advance` transaction as
   an exception.
2. **Programmer errors / broken invariants** (a missing pipeline id, an interrupted thread, a
   misconfigured registry or settings). These fail fast and are not caught — they indicate a bug or an
   impossible state, not a business outcome.

The result: business logic in `TaskMachine`, `PipelineEngine`, and `PipelineControl` reads
as a straight-line state machine. Failure handling is not scattered through it as `try/catch`; it is
concentrated at the one external boundary and expressed everywhere else as `ErrorCode` data.

## The two kinds of failure, side by side

| | External-call failure | Business-rule failure |
|---|---|---|
| Examples | per-call timeout, HTTP 5xx/429, connection reset | job reported FAILED, condition time-to-live expired, execution timeout |
| Representation | **exception** (`CallTimeoutException` / `CallFailedException` — the client's closed failure vocabulary) | **value** (`ErrorCode` on the task row) |
| Thrown? | yes — by the `client` package | no — never thrown |
| Where handled | caught once in `TaskMachine.runExternalCall` (wrapping execute/check/postCheck), translated to an `ErrorCode` | written directly to the row by `TaskMachine` |
| Lifetime | dies at the `TaskMachine` boundary | durable; part of run history; drives retry-or-fail |
| Retryable? | yes (re-run is a fresh, idempotent attempt) | `JOB_FAILED`/`EXECUTION_TIMEOUT` retry; `TIME_TO_LIVE_EXPIRED` does not |

The translation is deliberately small and total. Every external exception maps to one canonical
`ErrorCode`:

| Thrown at the boundary | Becomes |
|---|---|
| `InfraManagerClient.CallTimeoutException` (per-call timeout exceeded) | `ErrorCode.CALL_TIMEOUT` |
| `InfraManagerClient.CallFailedException` — any other failed call (HTTP error, connection failure, rejection, malformed/empty response), incl. a `TaskType` guard rejecting a null/blank job id or null status | `ErrorCode.CHECK_ERROR` |

The boundary (`TaskMachine.runExternalCall`, the one helper wrapping execute/check/postCheck) catches
**only** `CallTimeoutException` and `CallFailedException`. `CallInterruptedException` (a shutdown
interrupt) and any *other* `RuntimeException` (a genuine bug) are **not** caught — they propagate out of
`advance` (fail-fast) rather than being mis-recorded as a business `CHECK_ERROR`. See
[exception-cases.md](exception-cases.md) §1.

Business outcomes never pass through that table — they are decided by the polling logic and written
straight to the row:

| Business outcome | `ErrorCode` |
|---|---|
| TERRAFORM poll reported the job FAILED | `JOB_FAILED` |
| TERRAFORM job ran past its execution timeout | `EXECUTION_TIMEOUT` |
| CONDITION never met within its time-to-live | `TIME_TO_LIVE_EXPIRED` |

## Where each layer sits

```
 client/     throws        ── external-call failures live here and only here
   InfraManagerClient          (interface; HTTP in prod, faked in tests). The production adapter,
                               driven by the ADR-021 runner, owns the per-call timeout and raises
                               CallTimeoutException / CallInterruptedException (nested in the client).
      │  (exception crosses exactly one boundary)
      ▼
 service/    catches+translates
   TaskType impls            call the client; a TaskType also guards a null/malformed response by
                             throwing, so it surfaces as an external-call failure (never an NPE)
   TaskMachine               the single translation point (one `runExternalCall` helper wrapping
                             execute/check/postCheck): catch CallTimeoutException → CALL_TIMEOUT, catch
                             CallFailedException → CHECK_ERROR. CallInterruptedException and any
                             non-CallException RuntimeException (a bug) are NOT caught — they propagate
                             (fail-fast); business outcomes (TaskProgress) → ErrorCode value
   PipelineEngine            no business try/catch — pure state transitions in one transaction

 (ADR-021 runner, out of this module) catches RuntimeException PER PIPELINE: an infrastructure
                             failure (e.g. an optimistic-lock clash) is logged and that pipeline is
                             skipped, never aborting the whole sweep
```

`TaskMachine` wraps **every** `TaskType` call — `execute`, `check`, and `postCheck` — in one
`runExternalCall` helper, the only `try/catch` in the business layer, and it does nothing but classify:

```java
private Optional<TaskProgress> runExternalCall(Task task, Supplier<TaskProgress> call, boolean recordObservation) {
    try {
        return Optional.of(call.get());           // the task type calls InfraManagerClient
    } catch (InfraManagerClient.CallTimeoutException exception) {
        log.warn(...);
        if (recordObservation) observations.recordCheck(task, CheckSignal.CALL_TIMEOUT);
        retryOrFail(task, ErrorCode.CALL_TIMEOUT); // external timeout → value
        return Optional.empty();
    } catch (InfraManagerClient.CallFailedException exception) {
        log.warn(...);
        if (recordObservation) observations.recordCheck(task, CheckSignal.API_ERROR);
        retryOrFail(task, ErrorCode.CHECK_ERROR);  // external failure (incl. a null/malformed response) → value
        return Optional.empty();
    }
}
// CallInterruptedException and any non-CallException RuntimeException are NOT caught — a shutdown interrupt
// and a genuine bug both propagate out of advance (fail-fast). A present result is the business value:
// Succeeded | Pending | Failed(ErrorCode, retryable).
```

## The exceptions we *do* keep (and why)

These exception types survive, each for a precise reason — none is a business outcome.

1. **`InfraManagerClient.CallTimeoutException`** and **`InfraManagerClient.CallFailedException`** (nested
   in `client/InfraManagerClient.java`) — the client's closed failure vocabulary. `CallTimeoutException`
   signals that one InfraManager call exceeded the per-call timeout; `CallFailedException` signals any
   other failed call (HTTP error, connection failure, rejection, malformed/empty response — including a
   `TaskType` guard rejecting a null/blank job id or null status). The production adapter (driven by the
   ADR-021 runner, which owns the timeout) raises them, translating its transport exceptions into these
   types so it never leaks a raw `RuntimeException`. They exist so the boundary can distinguish a timeout
   (`CALL_TIMEOUT`) from any other call failure (`CHECK_ERROR`), and so it can catch exactly the
   external-call failures and let a genuine bug fail fast. Neither travels past `TaskMachine`.

2. **`DataIntegrityViolationException`** (Spring, caught in `service/PipelineCreator.java`) — the
   `active_target` unique violation when two creates race for one target. This is the one place an
   infrastructure exception is used as a **control signal**: the catch is targeted (only this type),
   and it compensates by returning the existing active run. This is how "one active pipeline per
   target" is enforced by the database rather than by application locking (ADR-016 §4).

3. **`OptimisticLockingFailureException`** (Spring/JPA, via `Task.@Version`) — a cancel that commits
   while an `advance` is mid-call makes the advance's stale save fail the version check. It is **not
   caught in the business layer**; it propagates out of `PipelineEngine.advance` and is absorbed by the
   **ADR-021 runner's** per-pipeline `catch (RuntimeException)` (out of this module's scope), which logs
   and skips that pipeline for the sweep. The terminal `CANCELLED` state is preserved; nothing is
   corrupted.

A fourth, **`InfraManagerClient.CallInterruptedException`**, is the fail-fast guard: `runExternalCall`
catches only `CallTimeoutException`/`CallFailedException`, so an interrupt is **not caught** — it simply
propagates out of `advance` (a shutdown interrupt aborts the step instead of being recorded as a business
`CHECK_ERROR`). Likewise `IllegalArgumentException` for a missing pipeline id fails fast. Neither is a
business outcome.

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
  correctness. They **ride the engine's `advance` transaction** rather than a separate `REQUIRES_NEW`
  tx — the maximal async-observation split was rejected (ADR-016 Option B), and at this scale only one
  task is serviced per `advance`. A failed observation write rolls back and **retries the whole
  advance**, which cannot corrupt state (the task row is atomic), so the simplification keeps the ADR's
  correctness-only guarantee. `Observations` is resilient to the common case (a missing attempt row is
  a no-op). If
  durable-under-failure observation is ever required, moving the writes to a `REQUIRES_NEW` boundary is
  the documented upgrade.

## How future features fit without changing the rule

- **More task kinds** — a new `TaskType` implementation (resolved by name, not a `switch`) brings its
  own `execute`/`check`/`postCheck`. Its failure modes are new `ErrorCode` values returned as `TaskProgress.Failed`,
  still written as data, still translated from external exceptions at the same one boundary.
- **Task Post-Check** — a post-completion verification failure is another business outcome: a new
  `ErrorCode` written to the row, not a thrown exception.
- **Event Outbox** — emitting an event on a terminal transition is a write in the *same transaction*
  as the state change. A failure to enqueue is an infrastructure failure (it rolls the transaction
  back), never a business `ErrorCode`.

See [extensibility.md](extensibility.md) for where these plug in.
