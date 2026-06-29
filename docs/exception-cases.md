# Exception Cases — the complete catalogue

> Companion to [exception-strategy.md](exception-strategy.md). That document states the *rule* (business
> failures are values; only infrastructure failures are exceptions). **This document enumerates every
> concrete case in the code where an exception is thrown, caught, translated, propagated, or used as a
> control signal** — so a reviewer can confirm no exception is swallowed and every one is handled on
> purpose.
>
> Each row is a real site in `src/main/java/com/bff/pipeline/`. "Business failures" (which are *values*,
> never exceptions) are listed last for contrast.

## 1. External-call failures — a closed exception vocabulary, translated once in `StepRunner`

`InfraManagerClient` signals a call failure with one of three nested exceptions — a closed vocabulary.
The **`TimeBoundedInfraManagerClient`** decorator (`@Primary`, on `imCallPool`) is where the vocabulary
is actually raised: `TimeoutException` during `Future.get(timeout)` → `CallTimeoutException`;
`InterruptedException` → `CallInterruptedException` (thread interrupt restored); `ExecutionException` →
cause unwrapped, preserving the delegate's closed vocabulary. The only `try/catch` in the service
layer for external-call failures is **`StepRunner.runStep`** (phase A, **outside any transaction** —
it runs between tx1 claim and tx2 report), the single boundary that wraps `TaskType.execute`,
`TaskType.check`, and `TaskType.postCheck`. It catches **only `CallTimeoutException` and
`CallFailedException`** and translates them to `StepOutcome` variants; `CallInterruptedException`
and any *other* `RuntimeException` (a genuine bug) are not caught and propagate (fail-fast — cases 6/7).

| # | Exception | Thrown by | Caught at | Handling | Becomes |
|---|---|---|---|---|---|
| 1 | `InfraManagerClient.CallInterruptedException` | `TimeBoundedInfraManagerClient` when `InterruptedException` occurs during `Future.get(timeout)` on the `imCallPool` | **not caught** by `StepRunner.runStep` (a distinct `final` type, not matched by the timeout/failed catches) | propagates out of `StepRunner.runStep` — fail-fast; caught by `PipelineScheduler.drain`, which restores the interrupt and aborts the sweep (see §3 / case 7) | _(propagates; see §3 / case 7)_ |
| 2 | `InfraManagerClient.CallTimeoutException` | `TimeBoundedInfraManagerClient` when `TimeoutException` occurs during `Future.get(timeout)` on the `imCallPool` (one call exceeded the per-call timeout) | `StepRunner.runStep` | **logged** (WARN); on a `check`/`postCheck` (not `execute`) also records a `CALL_TIMEOUT` check observation via `StepOutcome.callTimeout` applied in tx2 | `ErrorCode.CALL_TIMEOUT` (retryable) |
| 3 | `InfraManagerClient.CallFailedException` | `TimeBoundedInfraManagerClient` when `ExecutionException` is unwrapped and the cause is `CallFailedException` (HTTP 5xx/4xx, connection reset, rejection, malformed/empty response) **or** a `TaskType` guard (§2) | `StepRunner.runStep` | **logged** (WARN); on a `check`/`postCheck` also records an `API_ERROR` check observation via `StepOutcome.callFailed` applied in tx2 | `ErrorCode.CHECK_ERROR` (retryable) |

Because `StepRunner.runStep` wraps `execute`, `check`, and `postCheck`, a post-check that calls the
InfraManager is translated exactly like a check — a `postCheck` timeout/failure becomes
`CALL_TIMEOUT`/`CHECK_ERROR` (via the corresponding `StepOutcome` variant applied in tx2), never an
exception escaping `StepRunner.runStep`.

**Why a closed vocabulary, not a broad `catch (RuntimeException)`.** An earlier version caught any
`RuntimeException` and turned it into `CHECK_ERROR`. That risked converting a genuine programmer bug in
our own code (e.g. an NPE in deadline math, or a bug inside a `TaskType`) into a *business* `CHECK_ERROR`
and silently retrying it. The boundary now catches only `CallTimeoutException` and `CallFailedException`, so:

- an **external** failure (timeout / failed call) is handled — translated to a `StepOutcome` value,
  logged, retried when tx2 applies the outcome;
- a **bug** (any other `RuntimeException`) and a shutdown **interrupt** (`CallInterruptedException`) are
  **not** caught: they propagate out of `StepRunner.runStep` and are absorbed by
  `PipelineScheduler.drain`'s per-pipeline `catch` (logged, the pipeline skipped — case 7; the failed
  pipeline retains its stamped lease and is reclaimed after expiry). A bug surfaces loudly instead of
  hiding as a business failure.

This is the contract the `client` package owns: `TimeBoundedInfraManagerClient` (and any delegate
adapter) must translate its transport failures (Apache/JDK HTTP exceptions, etc.) into the closed
vocabulary; it must not leak a raw `RuntimeException`.

## 2. `TaskType` guards — turn an unusable external value into an external-call failure

A `TaskType` never returns a corrupt value to the engine; it throws `CallFailedException`, so the value
joins case 3 above (→ `CHECK_ERROR`) instead of becoming an NPE later. These are the trust-boundary null
guards.

| # | Exception | Thrown by | Condition | Becomes |
|---|---|---|---|---|
| 4 | `InfraManagerClient.CallFailedException` | `TerraformTask.execute` | InfraManager returned a `null`/blank job id (the job did not really start) | `CHECK_ERROR` (via case 3) |
| 5 | `InfraManagerClient.CallFailedException` | `TerraformTask.check` | InfraManager returned a `null` poll status (a read failure, not a job outcome) | `CHECK_ERROR` (via case 3) |

## 3. Fail-fast — programmer errors / broken invariants / bad config (never caught)

These indicate a bug, an impossible state, or misconfiguration. They are **not** caught; they fail the
operation (or application startup) immediately.

| # | Exception | Thrown by | Condition | Reaches |
|---|---|---|---|---|
| 6 | any non-`CallException` `RuntimeException` (e.g. `NullPointerException`, `IllegalStateException`) | a bug in a `TaskType` / `StepRunner` / `StepReporter` / `Observations` while servicing an advance | a real defect, not an external failure | propagates out of `StepRunner.runStep` or `StepReporter.report` → case 7 (fail-fast, never `CHECK_ERROR`) |
| 7 | _(any propagating `RuntimeException` from `PipelineWorker.pollOnce`)_ | propagates through `PipelineWorker.pollOnce()` | **`PipelineScheduler.drain`** per-pipeline `catch (RuntimeException)` (in this module) | logged (WARN), that pipeline skipped for the sweep; the failed pipeline keeps its tx1-stamped lease and is excluded from the next claim scan until lease expiry — same crash-recovery semantics as ADR-021 Decision 5 |
| 7a | `InfraManagerClient.CallInterruptedException` (from case 1) | propagates through `PipelineWorker.pollOnce()` | **`PipelineScheduler.drain`** catches it before the general `RuntimeException` catch | thread interrupt restored, drain returns immediately — the JVM shutdown signal aborts the sweep; no pipeline is half-processed |
| 8 | `IllegalStateException` | `TaskTypeRegistry` constructor | a `TaskType` bean has a `null`/blank `taskName()`, or two beans claim the same name | **application startup fails** (a misconfiguration must not boot) |
| 9 | `IllegalArgumentException` | `PipelineSettings` compact constructor | a required `pipeline.*` key is missing, or a duration is zero/negative, or `max-fail-count < 1` | **application startup fails** (incomplete/invalid config must not boot — pre-empts a later NPE/busy-loop in `TaskSettings`) |
| 10 | `IllegalArgumentException` | `PipelineControl.cancel` | the pipeline id does not exist | propagates; for cancel via REST it becomes `400` (§5). (In the worker path, a pipeline not found in `loadStepContext` causes `StepReporter.report` to no-op gracefully — not an `IllegalArgumentException`.) |

The value/transport records also fail fast in their compact constructors when handed an invalid value (a
malformed value, not a business outcome): `dto/TerraformPoll` rejects a not-finished-yet-succeeded poll
(`IllegalArgumentException`); `model/RecipeStep` rejects a blank `taskName` (`IllegalArgumentException`)
or null `operation` (`NullPointerException`, via `Objects.requireNonNull`); `model/Recipe` rejects an
empty step list (`IllegalArgumentException`); `model/TaskProgress` null-guards `Pending.observed` /
`Failed.reason` (`NullPointerException`). These are construction-time invariant guards that never reach
the engine.

Note: a task whose stored `taskName` resolves to no registered type is **not** in this table — that is a
*business value* (`UNKNOWN_TASK`, §6), not an exception, so a removed/renamed task type degrades to a
clean failure rather than failing the run with a thrown error.

## 4. Infrastructure exceptions / execution-layer control paths

| # | Exception or path | Thrown / triggered by | Caught / resolved at | Handling |
|---|---|---|---|---|
| 11 | `DataIntegrityViolationException` (Spring) | `PipelineInserter.insert` → `saveAndFlush`, when the `uq_pipeline_active_target` unique constraint rejects a second active run for one target | `PipelineCreator.create` | **control signal.** Only the active-target violation is recovered (recognized by constraint name, message fallback): return the existing active run. The insert is retried (bounded, `DUPLICATE_CREATE_RETRY_LIMIT`) because the active run can terminalize in the gap, freeing the target. Any *other* integrity violation is re-thrown — a real bug. If the retries are exhausted, the last violation is re-thrown. |
| 12 | `OptimisticLockingFailureException` (Spring/JPA, via `Task.@Version`) | flush/commit of `StepReporter.report` (tx2) in a residual concurrent scenario not already serialized by the claim fencing | **not caught in the service layer** | propagates out of `StepReporter.report`; absorbed by `PipelineScheduler.drain`'s per-pipeline catch (case 7, in this module). The primary guard against cancel-vs-worker clobber is the claim fencing (cases 16–18 below); `@Version` is a secondary backstop. The terminal `CANCELLED` state is preserved; nothing is corrupted. |
| 13 | `java.util.concurrent.TimeoutException` | `Future.get(timeout)` inside `TimeBoundedInfraManagerClient.withTimeout` when the delegate call exceeds `apiCallTimeout` | `TimeBoundedInfraManagerClient.withTimeout` | future cancelled, re-thrown as `InfraManagerClient.CallTimeoutException` (→ case 2 path in `StepRunner`) |
| 14 | `java.lang.InterruptedException` | `Future.get(timeout)` inside `TimeBoundedInfraManagerClient.withTimeout` when the worker thread is interrupted | `TimeBoundedInfraManagerClient.withTimeout` | future cancelled, thread interrupt restored, re-thrown as `InfraManagerClient.CallInterruptedException` (→ case 1 / case 7a path) |
| 15 | `java.util.concurrent.ExecutionException` | `Future.get(timeout)` inside `TimeBoundedInfraManagerClient.withTimeout` when the delegate call threw | `TimeBoundedInfraManagerClient.withTimeout` | cause unwrapped; if `RuntimeException`, re-thrown directly (preserving the delegate's closed vocabulary — usually `CallFailedException` → case 3 path); otherwise wrapped in `IllegalStateException` |
| 16 | stale `claimed_by` token — no-op guarded write-back | `StepReporter.report` after `findByIdForUpdate`: the pipeline's `claimed_by` no longer matches the worker's claim token (lease expired and another worker reclaimed; or Case A cancel cleared the claim) | `StepReporter.report` ownership guard | the entire report is silently no-op'd — no task or pipeline row is updated; the stale straggler cannot clobber state (ADR-021 Decision 4) |
| 17 | Cancel Case A — idle pipeline (no live claim or lease expired) | `PipelineControl.cancel` → `pipelines.cancelIfIdle(id, now)` | `PipelineControl.cancel` (rows updated != 0 path) | one guarded `UPDATE` atomically sets `status = CANCELLED`, clears `claimed_by`/`claimed_until`/`active_target`, and prevents straggler resurrection; then all non-terminal tasks are cancelled by `TaskCanceller.cancelNonTerminal`. Idempotent (RUNNING guard): a re-cancel or already-terminal pipeline is a 0-row no-op, falling through to Case B. |
| 18 | Cancel Case B — live-lease pipeline (worker holds the claim) | `PipelineControl.cancel` when Case A's `cancelIfIdle` returns 0 rows → `pipelines.requestCancel(id, now)` | `PipelineControl.cancel` (0-rows path); the flag is consumed by `StepReporter.report` / `PipelineWorker.process` | sets `cancel_requested = true` and `next_due_at = now()` to wake the pipeline; the claim-holding worker reads the flag at its safe points (post-claim check and inside tx2) and applies `CANCELLED` itself — the worker is the sole status writer for a live-lease pipeline. Idempotent (RUNNING guard): already-terminal pipelines are a 0-row no-op. |

## 5. REST layer — `GlobalAdvice` (`@RestControllerAdvice`)

The seam for the REST layer added later. No exception is swallowed: the catch-all logs the cause.

| # | Handler | Catches | Response | Notes |
|---|---|---|---|---|
| 19 | `onBadRequest` | `IllegalArgumentException` (e.g. case 10) | `400` + `ErrorResponse("BAD_REQUEST", message)` | a client/usage error; the message is safe to return |
| 20 | `onUnexpected` | `Exception` (anything else uncaught) | `500` + `ErrorResponse("INTERNAL_ERROR", "unexpected error")` | **logs the exception with its stack trace** before returning a generic body — the trace is never dropped |

## 6. Business failures — values, NOT exceptions (listed for contrast)

These are the expected outcomes of running infrastructure work. They are written to the task row as an
`ErrorCode` and **never thrown**. They are decided by `TaskType.check` (as a `TaskProgress.Failed` value)
or by the engine, then persisted by `TaskMachine`.

| `ErrorCode` | Decided by | Retryable? |
|---|---|---|
| `JOB_FAILED` | `TerraformTask.check` — the Terraform poll reported the job FAILED | yes |
| `EXECUTION_TIMEOUT` | `TerraformTask.check` — the job ran past its per-task execution timeout | yes |
| `TIME_TO_LIVE_EXPIRED` | `ConditionCheckTask.check` — the condition was never met within its TTL | **no** (the wait window is gone) |
| `UNKNOWN_TASK` | `TaskMachine.failUnknownTask` — the stored `taskName` resolves to no registered `TaskType` | **no** |
| `CALL_TIMEOUT` | `StepRunner` translating case 2 → `StepOutcome.callTimeout` → `TaskMachine.applyOutcome` in tx2 | yes |
| `CHECK_ERROR` | `StepRunner` translating case 3 → `StepOutcome.callFailed` → `TaskMachine.applyOutcome` in tx2 | yes |

`CALL_TIMEOUT` and `CHECK_ERROR` originate as exceptions (§1) but are *persisted as values*: once
translated they are ordinary business failures and drive the same retry-or-fail accounting.

## Invariant a reviewer can check

- Exactly **one external-call translation** `try/catch` exists in the service layer:
  `StepRunner.runStep` (and its `dispatch`/`poll` helpers), which wraps every InfraManager-backed
  call (`execute`/`check`/`postCheck`) and catches only `CallTimeoutException` and
  `CallFailedException`, translating them to sealed `StepOutcome` variants. `StepReporter.report`
  and `TaskMachine.applyOutcome` (tx2) have **no** external-call try/catch — they only apply the
  precomputed `StepOutcome`. The only other `catch` in the service layer is `PipelineCreator`'s
  targeted `DataIntegrityViolationException` control-signal catch (§4, case 11), which *recovers*
  rather than translates; `PipelineScheduler.drain` has two catches for per-pipeline isolation
  (`CallInterruptedException` for shutdown abort, `RuntimeException` for skip-and-continue) — both
  are deliberate isolation catches, not business-failure translators.
- No `catch` block is empty and none returns `null` on error. The two infrastructure catches that
  recover (`PipelineCreator`, `GlobalAdvice`) are type-targeted and either verified before acting
  (constraint name) or log the cause. The external-call catches log before translating.
- `CallInterruptedException` and any non-`CallException` `RuntimeException` are never caught in the
  business-logic layer — an interrupt and a bug both fail fast out of `StepRunner` and are handled
  only at the sweep-isolation boundary (`PipelineScheduler.drain`).
- Business failures never appear in a `throw`.
