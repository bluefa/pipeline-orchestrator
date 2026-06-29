# Exception Cases — the complete catalogue

> Companion to [exception-strategy.md](exception-strategy.md). That document states the *rule* (business
> failures are values; only infrastructure failures are exceptions). **This document enumerates every
> concrete case in the code where an exception is thrown, caught, translated, propagated, or used as a
> control signal** — so a reviewer can confirm no exception is swallowed and every one is handled on
> purpose.
>
> Each row is a real site in `src/main/java/com/bff/pipeline/`. "Business failures" (which are *values*,
> never exceptions) are listed last for contrast.

## 1. External-call failures — a closed exception vocabulary, translated once in `TaskMachine`

`InfraManagerClient` signals a call failure with one of three nested exceptions — a closed vocabulary.
The only `try/catch` in the business layer is `TaskMachine.runExternalCall`, the single helper that wraps
**every** InfraManager-backed call — `TaskType.execute`, `TaskType.check`, and `TaskType.postCheck`. It
catches **only `CallTimeoutException` and `CallFailedException`** and translates them; `CallInterruptedException`
and any *other* `RuntimeException` (a genuine bug) are not caught and propagate (fail-fast — cases 6/7).

| # | Exception | Thrown by | Caught at | Handling | Becomes |
|---|---|---|---|---|---|
| 1 | `InfraManagerClient.CallInterruptedException` | production adapter when the calling thread is interrupted (e.g. shutdown) | **not caught** (a distinct `final` type, not matched by the timeout/failed catches) | propagates out of `advance` — fail-fast, never recorded as a business outcome | _(propagates; see §4 / case 7)_ |
| 2 | `InfraManagerClient.CallTimeoutException` | production adapter when one call exceeds the per-call timeout | `TaskMachine.runExternalCall` | **logged** (WARN), then `retryOrFail`; on a `check`/`postCheck` (not `execute`) also records a `CALL_TIMEOUT` check observation | `ErrorCode.CALL_TIMEOUT` (retryable) |
| 3 | `InfraManagerClient.CallFailedException` | production adapter (HTTP 5xx/4xx, connection reset, rejection, malformed/empty response) **or** a `TaskType` guard (§2) | `TaskMachine.runExternalCall` | **logged** (WARN), then `retryOrFail`; on a `check`/`postCheck` also records an `API_ERROR` check observation | `ErrorCode.CHECK_ERROR` (retryable) |

Because the same `runExternalCall` wraps `execute`, `check`, and `postCheck`, a post-check that calls the
InfraManager is translated exactly like a check — a `postCheck` timeout/failure becomes
`CALL_TIMEOUT`/`CHECK_ERROR`, never an exception escaping `advance`.

**Why a closed vocabulary, not a broad `catch (RuntimeException)`.** An earlier version caught any
`RuntimeException` and turned it into `CHECK_ERROR`. That risked converting a genuine programmer bug in
our own code (e.g. an NPE in deadline math, or a bug inside a `TaskType`) into a *business* `CHECK_ERROR`
and silently retrying it. The boundary now catches only `CallTimeoutException` and `CallFailedException`, so:

- an **external** failure (timeout / failed call) is handled — translated to a value, logged, retried;
- a **bug** (any other `RuntimeException`) and a shutdown **interrupt** (`CallInterruptedException`) are
  **not** caught: they propagate out of `advance`, roll the transaction back, and are absorbed by the
  ADR-021 runner's per-pipeline `catch` (logged, the pipeline skipped — case 7). A bug surfaces loudly
  instead of hiding as a business failure.

This is the contract the `client` package owns: the production adapter must translate its transport
failures (Apache/JDK HTTP exceptions, etc.) into a `CallFailedException`; it must not leak a raw
`RuntimeException`.

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
| 6 | any non-`CallException` `RuntimeException` (e.g. `NullPointerException`, `IllegalStateException`) | a bug in a `TaskType` / engine / `Observations` while servicing an advance | a real defect, not an external failure | propagates out of `advance` → case 7 (fail-fast, never `CHECK_ERROR`) |
| 7 | _(any of the above propagating)_ | `PipelineEngine.advance` | — | absorbed by the **ADR-021 runner's** per-pipeline `catch (RuntimeException)` (out of scope here): logged, that pipeline skipped for the sweep; its transaction rolled back, so nothing is half-written |
| 8 | `IllegalStateException` | `TaskTypeRegistry` constructor | a `TaskType` bean has a `null`/blank `taskName()`, or two beans claim the same name | **application startup fails** (a misconfiguration must not boot) |
| 9 | `IllegalArgumentException` | `PipelineSettings` compact constructor | a required `pipeline.*` key is missing, or a duration is zero/negative, or `max-fail-count < 1` | **application startup fails** (incomplete/invalid config must not boot — pre-empts a later NPE/busy-loop in `TaskSettings`) |
| 10 | `IllegalArgumentException` | `PipelineEngine.advance` / `PipelineControl.cancel` | the pipeline id does not exist | propagates; for cancel via REST it becomes `400` (§5). A non-existent id is a broken invariant (the runner only advances ids it read from the DB). |

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

## 4. Infrastructure exceptions used as control signals / absorbed by the runner

| # | Exception | Thrown by | Caught at | Handling |
|---|---|---|---|---|
| 11 | `DataIntegrityViolationException` (Spring) | `PipelineInserter.insert` → `saveAndFlush`, when the `uq_pipeline_active_target` unique constraint rejects a second active run for one target | `PipelineCreator.create` | **control signal.** Only the active-target violation is recovered (recognized by constraint name, message fallback): return the existing active run. The insert is retried (bounded, `DUPLICATE_CREATE_RETRY_LIMIT`) because the active run can terminalize in the gap, freeing the target. Any *other* integrity violation is re-thrown — a real bug. If the retries are exhausted, the last violation is re-thrown. |
| 12 | `OptimisticLockingFailureException` (Spring/JPA, via `Task.@Version`) | flush/commit of `PipelineEngine.advance` when a concurrent `PipelineControl.cancel` committed `CANCELLED` mid-call and bumped the task version | **not caught in this module** | propagates out of `advance`; absorbed by the runner's per-pipeline catch (case 7). The terminal `CANCELLED` is preserved; nothing is corrupted. Symmetrically, a `cancel` that loses the race to a committing `advance` rolls back atomically and surfaces as a `500` via `GlobalAdvice` (the admin retries); cancel-vs-live-worker hardening is deferred to ADR-021. |

## 5. REST layer — `GlobalAdvice` (`@RestControllerAdvice`)

The seam for the REST layer added later. No exception is swallowed: the catch-all logs the cause.

| # | Handler | Catches | Response | Notes |
|---|---|---|---|---|
| 13 | `onBadRequest` | `IllegalArgumentException` (e.g. case 10) | `400` + `ErrorResponse("BAD_REQUEST", message)` | a client/usage error; the message is safe to return |
| 14 | `onUnexpected` | `Exception` (anything else uncaught) | `500` + `ErrorResponse("INTERNAL_ERROR", "unexpected error")` | **logs the exception with its stack trace** before returning a generic body — the trace is never dropped |

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
| `CALL_TIMEOUT` | `TaskMachine` translating case 2 | yes |
| `CHECK_ERROR` | `TaskMachine` translating case 3 | yes |

`CALL_TIMEOUT` and `CHECK_ERROR` originate as exceptions (§1) but are *persisted as values*: once
translated they are ordinary business failures and drive the same retry-or-fail accounting.

## Invariant a reviewer can check

- Exactly **one external-call translation** `try/catch` exists in the business/service layer:
  `TaskMachine.runExternalCall`, which wraps every InfraManager-backed call (`execute`/`check`/`postCheck`)
  and catches only `CallTimeoutException` and `CallFailedException`. The only other `catch` in the service
  layer is `PipelineCreator`'s targeted `DataIntegrityViolationException` control-signal catch (§4, case
  11), which *recovers* rather than translates; every other service method is exception-free straight-line
  code.
- No `catch` block is empty and none returns `null` on error. The two infrastructure catches that
  recover (`PipelineCreator`, `GlobalAdvice`) are type-targeted and either verified before acting
  (constraint name) or log the cause. The external-call catches log before translating.
- `CallInterruptedException` and any non-`CallException` `RuntimeException` are never caught in the
  business layer — an interrupt and a bug both fail fast.
- Business failures never appear in a `throw`.
