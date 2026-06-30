# Exception Handling Strategy

> Scope: the install/delete pipeline orchestrator (ADR-016 domain model and ADR-021 execution
> layer). This document is the contract for *how failures are represented and handled*. It is
> enforced by code review (the spring-java21 skill §5.7, §6) and referenced from `AGENTS.md`.

## The one rule

**Business failures are values; only infrastructure failures are exceptions.**

A task that fails because the Terraform job failed, the condition never came true, or a deadline
passed is **not** an exceptional event — it is an expected outcome of running infrastructure work.
It is recorded as an `ErrorCode` on the task row and becomes part of the visible run history. It is
never thrown.

An exception is reserved for two things only:

1. **External-call failures** at the InfraManager boundary (timeouts, HTTP errors, connection
   failures, a null/malformed response). These are thrown by the `client` package (or by a `TaskType`'s
   own guard on a null response), **caught at exactly one boundary** (`StepRunner.runStep`, phase A,
   outside any transaction), and translated into a `StepOutcome` value applied by tx2. No external
   exception escapes `StepRunner.runStep` as an exception.
2. **Programmer errors / broken invariants** (a missing pipeline id, an interrupted thread, a
   misconfigured registry or settings). These fail fast and are not caught — they indicate a bug or an
   impossible state, not a business outcome.

The result: business logic in `TaskMachine`, `StepReporter`, and `PipelineControl` reads
as a straight-line state machine. Failure handling is not scattered through it as `try/catch`; it is
concentrated at the one external boundary and expressed everywhere else as `ErrorCode` data.

## The two kinds of failure, side by side

| | External-call failure | Business-rule failure |
|---|---|---|
| Examples | per-call timeout, HTTP 5xx/429, connection reset | job reported FAILED, condition time-to-live expired, execution timeout |
| Representation | **exception** (`CallTimeoutException` / `CallFailedException` — the client's closed failure vocabulary) | **value** (`ErrorCode` on the task row) |
| Thrown? | yes — by the `client` package | no — never thrown |
| Where handled | caught once in `StepRunner.runStep` (phase A, outside any transaction, wrapping execute/check/postCheck), translated to a `StepOutcome`; `TaskMachine.applyOutcome` applies the outcome in tx2 with no try/catch | written directly to the row by `TaskMachine.applyOutcome` (via `StepReporter.report` in tx2) |
| Lifetime | dies at the `StepRunner.runStep` boundary (translated to a `StepOutcome` before tx2) | durable; part of run history; drives retry-or-fail |
| Retryable? | yes (re-run is a fresh, idempotent attempt) | `JOB_FAILED`/`EXECUTION_TIMEOUT` retry; `TIME_TO_LIVE_EXPIRED` does not |

The translation is deliberately small and total. Every external exception maps to one canonical
`ErrorCode`:

| Thrown at the boundary | Becomes |
|---|---|
| `InfraManagerClient.CallTimeoutException` (per-call timeout exceeded) | `ErrorCode.CALL_TIMEOUT` |
| `InfraManagerClient.CallFailedException` — any other failed call (HTTP error, connection failure, rejection, malformed/empty response), incl. a `TaskType` guard rejecting a null/blank job id or null status | `ErrorCode.CHECK_ERROR` |

The boundary (`StepRunner.runStep`, phase A — the external call runs outside any transaction,
between tx1 claim and tx2 report) catches **only** `CallTimeoutException` and `CallFailedException`
and translates them to `StepOutcome` variants. `CallInterruptedException` (a shutdown interrupt)
and any *other* `RuntimeException` (a genuine bug) are **not** caught — they propagate out of
`StepRunner.runStep` (fail-fast) rather than being mis-recorded as a business `CHECK_ERROR`. See
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
 client/     raises        ── external-call failures live here and only here
   TimeBoundedInfraManagerClient  (@Primary per-call-timeout decorator on imCallPool):
                               TimeoutException → CallTimeoutException; InterruptedException →
                               CallInterruptedException (thread interrupt restored, fail-fast);
                               ExecutionException → cause unwrapped (preserving the delegate's
                               closed vocabulary). The delegate (InfraManagerClient) is the
                               HTTP adapter in production or the test fake.
      │  (exception crosses exactly one boundary)
      ▼
 service/    catches+translates (phase A — OUTSIDE any transaction)
   TaskType impls            call the client; a TaskType also guards a null/malformed response by
                             throwing, so it surfaces as an external-call failure (never an NPE)
   StepRunner.runStep        the single translation point (wrapping execute/check/postCheck):
                             catch CallTimeoutException → StepOutcome.callTimeout, catch
                             CallFailedException → StepOutcome.callFailed. CallInterruptedException
                             and any non-CallException RuntimeException (a bug) are NOT caught —
                             they propagate (fail-fast)
      │  (StepOutcome value crosses into tx2)
      ▼
 service/    applies outcome (phase B — tx2 guarded write-back)
   StepReporter.report       verifies claim ownership (claimed_by token), then delegates to
                             TaskMachine.applyOutcome — no external calls, no try/catch for
                             external-call failures; business outcomes (StepOutcome variants)
                             → ErrorCode value written to the row
   TaskMachine.applyOutcome  pure state transitions in tx2 (no try/catch, no external call)

 PipelineScheduler.drain (IN this module) — two failure phases:
                             (a) claim phase (claimOneDue RuntimeException): logged (WARN),
                             drain ends immediately — prevents a DB-outage hammer loop;
                             adaptive backoff paces the next retry.
                             (b) process phase (PipelineWorker.process RuntimeException):
                             logged (WARN), that pipeline is skipped and the drain continues;
                             its stamped lease keeps it out of the next claim scan until expiry
                             (crash-recovery semantics). Single-pipeline failure does not starve
                             other pipelines.
                             CallInterruptedException (either phase): drain re-throws →
                             sweepOnce cancels remaining outstanding drain futures, restores the
                             interrupt, and throws InterruptedException → runSweep does not
                             reschedule (JVM shutdown signal aborts the sweep).
```

`StepRunner` wraps **every** `TaskType` call — `execute`, `check`, and `postCheck` — in the
`dispatch` or `poll` helper (collectively `runStep`), the only `try/catch` in the business layer,
and it does nothing but classify. The external call runs **outside any transaction** (between tx1
claim and tx2 report):

```java
// phase A — StepRunner.runStep (no @Transactional; runs between tx1 and tx2)
private StepOutcome dispatch(TaskType type, String target, Task task) {
    try {
        type.execute(target, task);
        return StepOutcome.dispatched(task.getJobId()); // success → value
    } catch (InfraManagerClient.CallTimeoutException e) {
        log.warn(...);
        return StepOutcome.callTimeout(true);  // external timeout → value
    } catch (InfraManagerClient.CallFailedException e) {
        log.warn(...);
        return StepOutcome.callFailed(true);   // external failure → value
    }
    // CallInterruptedException and any non-CallException RuntimeException are NOT caught —
    // a shutdown interrupt and a genuine bug both propagate (fail-fast).
}

// phase B — TaskMachine.applyOutcome (called by StepReporter.report inside tx2)
// No try/catch; applies the precomputed StepOutcome to the managed Task entity.
public void applyOutcome(Task task, StepOutcome outcome) {
    switch (outcome) {                           // sealed StepOutcome — exhaustive
        case StepOutcome.CallFailure cf -> { ... retryOrFail(task, cf.reason()); }
        case StepOutcome.Succeeded ignored -> complete(task);
        // ... etc.
    }
}
```

## The exceptions we *do* keep (and why)

These exception types survive, each for a precise reason — none is a business outcome.

1. **`InfraManagerClient.CallTimeoutException`** and **`InfraManagerClient.CallFailedException`** (nested
   in `client/InfraManagerClient.java`) — the client's closed failure vocabulary. `CallTimeoutException`
   signals that one InfraManager call exceeded the per-call timeout; `CallFailedException` signals any
   other failed call (HTTP error, connection failure, rejection, malformed/empty response — including a
   `TaskType` guard rejecting a null/blank job id or null status). The
   **`TimeBoundedInfraManagerClient`** decorator (`@Primary`, running delegate calls on `imCallPool`)
   owns the production per-call timeout and raises these exceptions — `TimeoutException →
   CallTimeoutException`; `ExecutionException` is unwrapped to expose the delegate's closed vocabulary.
   They exist so the boundary can distinguish a timeout (`CALL_TIMEOUT`) from any other call failure
   (`CHECK_ERROR`), and so it can catch exactly the external-call failures and let a genuine bug fail
   fast. Neither travels past `StepRunner.runStep`.

2. **`DataIntegrityViolationException`** (Spring, caught in `service/PipelineCreator.java`) — the
   `active_target` unique violation when two creates race for one target. This is the one place an
   infrastructure exception is used as a **control signal**: the catch is targeted (only this type),
   and it compensates by returning the existing active run. This is how "one active pipeline per
   target" is enforced by the database rather than by application locking (ADR-016 §4).

3. **`OptimisticLockingFailureException`** (Spring/JPA, via `Task.@Version`) — a secondary backstop.
   The cancel-vs-worker race is primarily handled by the claim fencing (ownership-guarded write-back in
   tx2 + cancel Case A/B — see below), which serializes concurrent writers: Case A fires only when no
   live claim exists and clears `claimed_by` atomically; Case B sets `cancel_requested` and the
   claim-holding worker applies `CANCELLED` under the row lock. If `@Version` does fire in a residual
   race, it propagates out of `StepReporter.report` and is absorbed by **`PipelineScheduler.drain`'s**
   per-pipeline `catch (RuntimeException)` (in this module), which logs and skips that pipeline for the
   sweep; the terminal `CANCELLED` state is preserved. Nothing is corrupted.

A fourth, **`InfraManagerClient.CallInterruptedException`**, is the fail-fast guard: `StepRunner.runStep`
catches only `CallTimeoutException`/`CallFailedException`, so an interrupt is **not caught** — it simply
propagates out of `StepRunner.runStep` (a shutdown interrupt aborts the step instead of being recorded
as a business `CHECK_ERROR`). `PipelineScheduler.drain` catches it specifically before the general
`RuntimeException` catch in both the claim phase and the process phase: it restores the thread interrupt
and re-throws, which surfaces as `ExecutionException.getCause()` in `sweepOnce`; `sweepOnce` then
cancels all remaining outstanding drain futures, restores the interrupt, and throws `InterruptedException`;
`runSweep` catches that and does **not** reschedule — the loop stops cleanly. Likewise
`IllegalArgumentException` for a missing pipeline id fails fast. Neither is a business outcome.

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
  correctness. They **ride tx2 (`StepReporter.report`)** rather than a separate `REQUIRES_NEW`
  tx — the maximal async-observation split was rejected (ADR-016 Option B), and at this scale only one
  task is serviced per claim-pull cycle. A failed observation write rolls back tx2 and the claim-pull
  cycle retries on the next claim, which cannot corrupt state (the task row is atomic), so the
  simplification keeps the ADR's correctness-only guarantee. `Observations` is resilient to the common
  case (a missing attempt row is a no-op). If durable-under-failure observation is ever required,
  moving the writes to a `REQUIRES_NEW` boundary is the documented upgrade.

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
