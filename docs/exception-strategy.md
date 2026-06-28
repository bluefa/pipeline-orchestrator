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
   failures). These are thrown by the `im` package, **caught at exactly one boundary**
   (`TaskMachine`), and immediately translated into an `ErrorCode`. No external exception escapes the
   reconcile transaction as an exception.
2. **Programmer errors / broken invariants** (an unknown recipe, a missing pipeline id, an
   interrupted thread). These fail fast and are not caught — they indicate a bug or an impossible
   state, not a business outcome.

The result: business logic in `TaskMachine`, `PipelineReconciliation`, and `PipelineControl` reads
as a straight-line state machine. Failure handling is not scattered through it as `try/catch`; it is
concentrated at the one external boundary and expressed everywhere else as `ErrorCode` data.

## The two kinds of failure, side by side

| | External-call failure | Business-rule failure |
|---|---|---|
| Examples | per-call timeout, HTTP 5xx/429, connection reset | job reported FAILED, condition TTL expired, execution timeout |
| Representation | **exception** (`CallTimeoutException`, any `RuntimeException` from `ImClient`) | **value** (`ErrorCode` on the task row) |
| Thrown? | yes — by the `im` package | no — never thrown |
| Where handled | caught once in `TaskMachine` (dispatch/poll), translated to an `ErrorCode` | written directly to the row by `TaskMachine` |
| Lifetime | dies at the `TaskMachine` boundary | durable; part of run history; drives retry-or-fail |
| Retryable? | yes (re-run is a fresh, idempotent attempt) | `JOB_FAILED`/`EXECUTION_TIMEOUT` retry; `TTL_EXPIRED` does not |

The translation is deliberately small and total. Every external exception maps to one canonical
`ErrorCode`:

| Thrown at the boundary | Becomes |
|---|---|
| `ImCall.CallTimeoutException` (per-call timeout exceeded) | `ErrorCode.CALL_TIMEOUT` |
| any other `RuntimeException` from `ImClient` (HTTP error, connection failure, rejection) | `ErrorCode.CHECK_ERROR` |

Business outcomes never pass through that table — they are decided by the polling logic and written
straight to the row:

| Business outcome | `ErrorCode` |
|---|---|
| TERRAFORM poll reported the job FAILED | `JOB_FAILED` |
| TERRAFORM job ran past its execution timeout | `EXECUTION_TIMEOUT` |
| CONDITION never met within its TTL | `TTL_EXPIRED` |

## Where each layer sits

```
 im/         throws        ── external-call failures live here and only here
   ImClient                    (interface; HTTP in prod, faked in tests)
   ImCall                      wraps a call in the per-call timeout → CallTimeoutException
      │  (exception crosses exactly one boundary)
      ▼
 reconcile/  catches+translates
   TaskMachine               the single translation point: catch CallTimeoutException → CALL_TIMEOUT,
                             catch RuntimeException → CHECK_ERROR; business outcomes → ErrorCode value
   PipelineReconciliation    no business try/catch — pure state transitions in one transaction
   Reconciler                catches RuntimeException PER PIPELINE: an infrastructure failure
                             (e.g. an optimistic-lock clash) is logged and that pipeline is skipped,
                             never aborting the whole tick
```

`TaskMachine.dispatch`, `pollTerraform`, and `pollCondition` each have the same shape — the only
`try/catch` in the business layer, and it does nothing but classify:

```java
try {
    result = imCall.withTimeout(() -> im.terraformJobStatus(task.getJobId()));
} catch (ImCall.CallTimeoutException e) {
    retryOrFail(task, ErrorCode.CALL_TIMEOUT);   // external timeout → value
    return;
} catch (RuntimeException e) {
    retryOrFail(task, ErrorCode.CHECK_ERROR);    // external error → value
    return;
}
// ...from here on, only business outcomes, all expressed as ErrorCode values
```

## The exceptions we *do* keep (and why)

Three exception types survive, each for a precise reason. None is a business outcome.

1. **`ImCall.CallTimeoutException`** (`im/ImCall.java`) — signals that one InfraManager call exceeded
   the per-call timeout. It exists so the boundary can distinguish a timeout (`CALL_TIMEOUT`) from any
   other call failure (`CHECK_ERROR`). It never travels past `TaskMachine`.

2. **`DataIntegrityViolationException`** (Spring, caught in `create/PipelineCreator.java`) — the
   `active_target` unique violation when two creates race for one target. This is the one place an
   infrastructure exception is used as a **control signal**: the catch is targeted (only this type),
   and it compensates by returning the existing active run. This is how "one active pipeline per
   target" is enforced by the database rather than by application locking (ADR-016 §4).

3. **`OptimisticLockingFailureException`** (Spring/JPA, via `Task.@Version`) — a cancel that commits
   while a reconcile is mid-call makes the reconcile's stale save fail the version check. It is **not
   caught in the business layer**; it propagates out of `PipelineReconciliation.reconcile` and is
   absorbed by `Reconciler.tick`'s per-pipeline `catch (RuntimeException)`, which logs and skips that
   pipeline for the tick. The terminal `CANCELLED` state is preserved; nothing is corrupted.

Programmer-error guards (`IllegalArgumentException` for an unknown recipe or a missing pipeline id,
`IllegalStateException` for an interrupted call) fail fast and are intentionally never caught.

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
  for diagnosis only; the reconciler never reads them, and losing them costs debuggability, never
  correctness.

## How future features fit without changing the rule

- **More task kinds** — a new `TaskKind` adds a dispatch/poll branch in `TaskMachine`. Its failure
  modes are new `ErrorCode` values, still written as data, still translated from external exceptions at
  the same one boundary.
- **Task Post-Check** — a post-completion verification failure is another business outcome: a new
  `ErrorCode` written to the row, not a thrown exception.
- **Event Outbox** — emitting an event on a terminal transition is a write in the *same transaction*
  as the state change. A failure to enqueue is an infrastructure failure (it rolls the transaction
  back), never a business `ErrorCode`.

See [extensibility.md](extensibility.md) for where these plug in.
