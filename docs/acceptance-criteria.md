# ADR-016 Acceptance Criteria & Test Matrix

> **Reconciled to the ADR-021 claim-pull execution model (D-D).** This matrix originally targeted the
> superseded `PipelineEngine.advance(...)` + `PipelineRepository.finish()`-CAS driver. That layer no
> longer exists. Execution is now the claim-pull chain
> `PipelineScheduler.runSweep` → `PipelineWorker.process` → `StepRunner.runStep` → `StepReporter.writeBack`,
> with claim acquisition in `PipelineClaimer.claimOneDue`. Terminalization is a **guarded write-back**
> (`StepReporter.converge`/`terminalize` under the pipeline row `FOR UPDATE` + the token-ownership check),
> not a `finish()` CAS. The old `PipelineEngineTest` / `PipelineEngineTransactionTest` coverage is
> consolidated into `PipelineExecutionTest`. The class/test references below have been remapped to the
> current code; the **criteria themselves are unchanged and still hold**, with one exception:
> **ADR-016 §4's duplicate-create is now 409 Conflict** (`PipelineAlreadyActiveException` /
> `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`), no longer an idempotent "return the existing run"
> (see `decisions-and-questions.md` D-C). Criteria D2/D3 below reflect that reversal.

The **definition of done** for this module, derived from
[ADR-016](adr/016-install-delete-pipeline-domain-model.md). Each ADR decision becomes one or more
concrete, testable criteria, each tied to the code and the test that proves it. This is the
self-review checklist; the review-round log at the bottom tracks the codex passes.

> **Scope.** This module is the **domain model** (ADR-016): durable state, the data model, the
> uniqueness rule, the failure semantics, and the lifecycle. In this repo the domain now ships
> **together with** the ADR-021 execution model (the claim-pull scheduler, worker pool, lease/token
> fencing, and crash-recovery loop). The criteria below still test the *domain* invariants; the tests
> drive them through the real claim-pull path (`PipelineClaimer.claimOneDue` → `PipelineWorker.process` →
> `StepReporter.writeBack`) rather than a standalone `advance()` entry point.

**Status legend:** ✅ met (code + test evidence) · ⚠️ gap (must fix) · 📦 deferred (out of v1 scope,
documented).

## A. Decision 1 — the database is the only state

| # | Criterion | Verified by | Status |
|---|---|---|---|
| A1 | Progress lives only in DB rows; each claim re-reads the pipeline + chain and resumes — no in-memory authority | `PipelineWorker.process` re-loads the pipeline and `findByPipelineIdOrderBySequenceAsc` chain on every claim | ✅ |
| A2 | A restart re-polls an IN_PROGRESS terraform job by the dispatch `response` stored on its latest `task_attempt` (crash recovery) — the expired lease is reclaimed and the IN_PROGRESS check reads the stored response | `PipelineExecutionTest.anExpiredLeaseIsReclaimedWithADifferentToken` + `aLostDispatchResponseRidesExecutionTimeoutThenSharesTheFailCount` | ✅ |
| A3 | A committed write-back is visible to a fresh read (own transaction); a stale straggler cannot clobber it | `StepReporter.writeBack` (`@Transactional`, own tx); `PipelineExecutionTest.aStaleTokenReportNoOpsAfterReclaim` / `aClaimStampsAFreshTokenAndLeaseAndBlocksASecondClaim` | ✅ |

## B. Decision 2 — two domain tables, a small durable state machine

| # | Criterion | Verified by | Status |
|---|---|---|---|
| B1 | Task lifecycle is BLOCKED→READY→IN_PROGRESS→DONE\|FAILED\|CANCELLED | `TaskStatus`, `TaskStateMachine.applyOutcome` | ✅ |
| B2 | Pipeline lifecycle is RUNNING→DONE\|FAILED\|CANCELLED | `PipelineStatus`, `StepReporter.converge` | ✅ |
| B3 | First task starts READY; the rest start BLOCKED and flip to READY when the predecessor is DONE | `PipelineInserter.insert` + `PipelineExecutionTest.installPromotesTheSuccessorInTheSameReportAndFinishes` | ✅ |
| B4 | The current task is the lowest-sequence non-terminal task | `StepReporter.currentTask` / `PipelineWorker.currentTask` (first non-terminal over the sequence-ordered chain) | ✅ |
| B5 | Pipeline status is a stored projection, written in the same tx as the task transition that changes it | `StepReporter.terminalize` runs inside the `writeBack` tx under the pipeline `FOR UPDATE` lock | ✅ |
| B6 | Five core enums + a closed `TaskOperation` (a task's type selects the executor, operation the action) | `entity/*` enums; the type is resolved by name (no `switch` on kind) | ✅ |
| B7 | A recipe (ordered task list) is a code default per type | `Recipes` | ✅ |
| B8 | A task's behaviour is a `TaskType` resolved **by name** through `TaskTypeRegistry`; a new kind self-registers (no enum/`switch` edit) | `TaskType`/`TaskTypeRegistry`; `TerraformTask`/`ConditionCheckTask`; `TaskTypeRegistryTest` | ✅ |

## C. Decision 3 — observation is separate from state

| # | Criterion | Verified by | Status |
|---|---|---|---|
| C1 | `task_attempt` = one row per attempt, `attempt_no = failCount + 1` | `ObservationRecorder.beginAttempt` + `ObservationTest.aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo` | ✅ |
| C2 | `task_check` is UPDATE-in-place (one row per attempt; counters grow, not rows) | `ObservationTest.aConditionPolledNotMetUpdatesOneCheckRowInPlace` | ✅ |
| C3 | The converge/write-back never reads the observation tables for correctness; the only read is `ObservationRecorder.currentAttempt`, which returns the latest attempt carrying the dispatch `response` handle (E2/E3) — a durable dispatch handle, not observation-derived state | `ObservationRecorder.currentAttempt` (sole read); `StepReporter.converge` / `TaskStateMachine` never query the tables | ✅ |
| C4 | Losing observations degrades debuggability only, never correctness — they can never roll back task progress | observations ride the same write-back tx; a failed write retries the whole write-back, which cannot corrupt the atomic task row — see Deferred | ✅ |

## D. Decision 4 — one active pipeline per target

| # | Criterion | Verified by | Status |
|---|---|---|---|
| D1 | At most one non-terminal pipeline per target (JPA unique constraint on `active_target`) | `Pipeline` `@UniqueConstraint`; `PipelineUniquenessTest.duplicateCreateForAnActiveTargetIsRejectedAsConflict` | ✅ |
| D2 | A duplicate create — of any type — is **rejected as 409 Conflict** (`PipelineAlreadyActiveException` / `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`), not an idempotent return-existing (D-C: the trigger is a human admin "try", not machine at-least-once) | `PipelineCreator.create` throws on the `active_target` violation; `PipelineUniquenessTest.duplicateCreate...` / `aDifferentTypeCreateForAnActiveTargetIsRejectedAsConflict` | ✅ |
| D3 | Only the `active_target` unique violation maps to 409; every other integrity violation is wrapped as `PipelinePersistenceException` (500), never leaked raw to the controller | `PipelineCreator.isActiveTargetViolation`/`namesActiveTargetConstraint` checks the constraint name before classifying | ✅ |
| D4 | A target is reusable once its prior run is terminal (`active_target` cleared on every terminalization) | `StepReporter.terminalize` / `PipelineControl.cancel` clear it; `PipelineUniquenessTest.aNewRunIsAllowedForATargetOnceItsPriorRunIsTerminal` | ✅ |

## E. Decision 5 — correctness rests on idempotency, not exactly-once

| # | Criterion | Verified by | Status |
|---|---|---|---|
| E1 | Dispatch is idempotent / at-least-once safe; a crash before storing the dispatch `response` heals by re-dispatch | `StepRunner.runStep` (READY → re-dispatch via `type.execute`); A2 reclaim/lost-response tests | ✅ |
| E2 | No `DISPATCHING` state; the dispatch `response` is recorded on the latest `task_attempt` | `TaskStatus` has no DISPATCHING; the dispatch outcome records the `response` via `ObservationRecorder.recordResponse` | ✅ |
| E3 | `(task_id, attempt_no)` is our logical attempt identity, not an InfraManager key | `TaskAttempt` unique `(task_id, attempt_no)` | ✅ |

## F. Decision 6 — bounded waiting and retry

| # | Criterion | Verified by | Status |
|---|---|---|---|
| F1 | `fail_count` per task; below `maxFailCount` → fresh re-run, at/above → FAILED | `TaskStateMachine.retryOrFail` + `PipelineExecutionTest.terraformFailureRetriesThenFailsAtMaxAndCascadeCancels` | ✅ |
| F2 | Per-call timeout → `CALL_TIMEOUT` (the `TimeBoundedInfraManagerClient` enforces the timeout; `StepRunner.runExternalCall` maps `CallTimeoutException`) | `TimeBoundedInfraManagerClientTest.aSlowCallBecomesCallTimeout` (+ `StepRunner.runExternalCall`) | ✅ |
| F3 | Per-task `executionTimeout` (TF) → `EXECUTION_TIMEOUT` | `TaskSettings.isPastDeadline` (executionTimeout path) + `PipelineExecutionTest.aLostDispatchResponseRidesExecutionTimeoutThenSharesTheFailCount` | ✅ |
| F4 | Per-task `timeToLive` (condition) → `TIME_TO_LIVE_EXPIRED` (no retry) | `TaskSettings.isPastDeadline` (timeToLive path) + `ErrorCode.TIME_TO_LIVE_EXPIRED` | ✅ |
| F5 | Both deadlines map to canonical `ErrorCode` values, not separate states | `ErrorCode`; no extra `TaskStatus` | ✅ |

## G. Decision 7 — minimal lifecycle

| # | Criterion | Verified by | Status |
|---|---|---|---|
| G1 | Two task kinds today (TERRAFORM_JOB, CONDITION_CHECK); the set is open via new `TaskType` impls | `TerraformTask`/`ConditionCheckTask` (a third kind is a new file, not an enum edit) | ✅ |
| G2 | Retry is a fresh run (no terminal resurrection) | `TaskStateMachine.retryOrFail` resets to READY | ✅ |
| G3 | Cancel converges directly to CANCELLED (no CANCELLING) and terminalizes every non-terminal task | `PipelineControl.cancel` + `TaskCanceller.cancelNonTerminal`; `PipelineControlTest.cancelTerminalizesEveryNonTerminalTaskAndFreesTheTarget` | ✅ |
| G4 | Cancel is a RUNNING-guarded transition — it can never resurrect a pipeline a converge already terminalized | Case A `PipelineRepository.cancelIfIdle` (guard `status='RUNNING' AND (claimed_by IS NULL OR claimed_until<now)`, 0-row = no-op); Case B `requestCancel` flag observed under the claim holder's `FOR UPDATE`; `PipelineControlTest.cancelDoesNotResurrectAPipelineThatAlreadyConvergedToTerminal` + `PipelineExecutionTest.aStaleStragglerCannotResurrectAfterCaseACancel` | ✅ |
| G5 | A FAILED pipeline marks the failing task FAILED and CANCELS the rest | `StepReporter.converge` (`anyTaskFailed` → `TaskCanceller.cancelNonTerminal`) + `PipelineExecutionTest.terraformFailureRetriesThenFailsAtMaxAndCascadeCancels` | ✅ |
| G6 | A terminal state is never resurrected | write-back token-ownership + pipeline `FOR UPDATE` on every terminalization; cancel's RUNNING guard; `Task.@Version` on the task race | ✅ |
| G7 | A task whose stored `taskName` resolves to no `TaskType` fails with `UNKNOWN_TASK` (a value, no NPE) | `StepRunner`/`TaskStateMachine.failUnknownTask` + `PipelineExecutionTest.anUnknownTaskNameFailsAsUnknownTask` | ✅ |

## H. Schema & persistence (JPA-generated, MySQL)

| # | Criterion | Verified by | Status |
|---|---|---|---|
| H1 | Tables, indexes, unique constraints are generated from JPA annotations — no Flyway / hand-written SQL | `@Table`/`@Index`/`@UniqueConstraint`; Hibernate DDL log | ✅ |
| H2 | MySQL target; tests run on H2 MySQL-mode without Docker | `application.yml` (MySQL) + test `application.yml` (H2 `MODE=MySQL`) | ✅ |
| H3 | "One active per target" works on MySQL despite no partial unique index (nullable `active_target` + unique; multiple NULLs allowed) | `Pipeline.activeTarget`; uniqueness tests | ✅ |
| H4 | The cancel/write-back task race is guarded — ordering is provided by the pipeline row `FOR UPDATE` lock (the single write-back writer), with `Task.@Version` as defensive optimistic locking | `Task.version` + `PipelineExecutionTest.cancelCaseBLiveLeaseOnlyRaisesTheFlagThenTheClaimHolderTerminalizes` / `aStaleStragglerCannotResurrectAfterCaseACancel` | ✅ |
| H5 | Indexes are JPA-declared and cover the actual queries; no orphan index | unique constraints `uq_task_pipeline_sequence`/`uq_task_attempt`/`uq_task_check_attempt`/`uq_pipeline_active_target` + the claim due-index back every repository query | ✅ |

## I. Exception strategy (external vs business)

| # | Criterion | Verified by | Status |
|---|---|---|---|
| I1 | Business failures are `ErrorCode` values on the row, never thrown | `TaskStateMachine.retryOrFail`/`failOutright` | ✅ |
| I2 | External-call failures are exceptions caught at exactly one boundary and translated to a `StepOutcome`/`ErrorCode` | the single `StepRunner.runExternalCall` helper (wrapping READY `execute` / IN_PROGRESS `check`) + `PipelineExecutionTest.aThrowingDispatchIsCheckErrorAndRetries` (+ the `TimeBoundedInfraManagerClientTest` CALL_TIMEOUT boundary) | ✅ |
| I3 | Interruption is a fail-fast runtime signal, NOT mapped to a business `ErrorCode`; likewise a raw (non-boundary) `RuntimeException` propagates | `StepRunner.runExternalCall` catches only `CallTimeout`/`CallFailed`, so an interrupt / raw bug propagates out of `PipelineWorker.process` (absorbed by `PipelineScheduler.drain`'s per-pipeline catch); `PipelineExecutionTest.aRawRuntimeExceptionFromTheClientPropagatesOutOfProcess` | ✅ |
| I4 | The duplicate-create catch is targeted (constraint-checked): the `active_target` violation → 409, all others → 500, never a raw leak | see D2/D3; `PipelineCreator.namesActiveTargetConstraint` | ✅ |
| I5 | Strategy is documented | `docs/exception-strategy.md` | ✅ |
| I6 | A null/blank job id or null poll from the boundary is guarded → `CHECK_ERROR` retry / fail-outright, never an NPE or a persisted null handle | `TerraformTask` guards + `PipelineExecutionTest.aMalformedDispatchResponseFailsTheTaskOutright` / `aResponseWithNoUsableJobIdsFailsTheTaskOutright` | ✅ |

## J. Code quality (spring-java21 skill §6) & extensibility

| # | Criterion | Verified by | Status |
|---|---|---|---|
| J1 | Constructor injection only; `Clock` injected; no direct `now()` in logic | all beans | ✅ |
| J2 | Closed-set switches are exhaustive, no `default` swallow | `TaskStateMachine.applyOutcome` / `StepRunner.runStep` enumerate the cases | ✅ |
| J3 | Interfaces are justified — `InfraManagerClient` (external boundary, prod + fake) and `TaskType` (genuine N-impl polymorphism, registry-resolved); static utility (`TaskSettings`) over a one-method bean | package scan | ✅ |
| J4 | Tests behavior-named, fixed `Clock`, fakes not mocks | test suite (`FakeInfraManagerClient`, `MutableClock`) | ✅ |
| J5 | Extension seams (more task kinds / outbox) documented, not pre-built | `docs/extensibility.md` | ✅ |

## Deferred / accepted limitations (📦)

- **Observations ride the write-back transaction** (no `REQUIRES_NEW` / separate bean). A failed
  observation write rolls back and retries the whole write-back, which cannot corrupt the atomic task
  row, so this honors the ADR's correctness-only guarantee. The `REQUIRES_NEW` boundary is the
  documented upgrade. 📦
- **Cancelled in-flight attempts** are ended as `task_attempt.status = CANCELLED` by `TaskCanceller`
  (which ends the open attempt before terminalizing the task), so the observation history matches the
  authoritative `task` row rather than leaving a stale `IN_PROGRESS` attempt behind a terminal task. ✅
- **Concurrent duplicate-create race** is verified sequentially (H2); the true row-level INSERT race
  is a MySQL/CI concern (the constraint itself is enforced by H2, which is what raises the 409). 📦
- **H2 renders the claim query as plain `FOR UPDATE`, not `SKIP LOCKED`** — the non-blocking
  concurrent-claim behavior is MySQL-only and unverified by the H2 suite (single-thread claim/lease is
  verified). A Testcontainers MySQL concurrent-claim test is the documented follow-up (D-B6). 📦
- **HTTP InfraManager adapter** and the response-code/summary observation fields are out of v1 scope
  (the host wires the `InfraManagerClient`); the per-call timeout is enforced by the
  `TimeBoundedInfraManagerClient` decorator over that delegate. 📦
- **429/503 differentiated back-pressure** is deferred — general back-pressure works (failures push
  `next_due_at` forward by `pollingInterval`), but Retry-After-aware backoff needs an exception-vocabulary
  extension (D-B2). 📦
- **Operational metrics** (claim QPS, stale-report discard, reclaim, slot retry, overshoot, latency) are
  an observability follow-up — no Micrometer wiring in this demo repo (D-B3). 📦

## Review-round log

*(Rounds 1–5 below reviewed the pre-ADR-021 domain tree, when execution was `PipelineEngine.advance` +
`finish()`-CAS. They are retained for history; the ADR-021 claim-pull reviews follow.)*

| Round | Reviewer | Verdict | P0 | P1 | P2 | Notes |
|---|---|---|---|---|---|---|
| 1 | codex (gpt-5.5 xhigh) | No | 0 | 4 | 3 | cancel CAS, observation isolation, dup-create catch, interrupt mapping; nits: default arm, cancelled-attempt status, @DataJpaTest tx |
| 1 | opus (code-reviewer) | No | 0 | 3 | 8 | **corroborated cancel CAS as the one blocker**; dup-create catch; @DataJpaTest tx (rated P1); nits incl. TTL-per-attempt, converge re-query, observation tx |
| 2 | codex (gpt-5.5 xhigh) | **Yes** | 0 | 0 | 2 | all 5 round-1 fixes VERIFIED; 2 new P2s |
| 3 | codex (gpt-5.5 xhigh) | **Yes** | 0 | 0 | 0 | both round-2 fixes VERIFIED; no remaining findings |
| 4 | codex (gpt-5.5 xhigh) | **Yes** | 0 | 0 | 0 | re-review of the pure-domain tree: no findings |
| 5 | codex / opus / sonnet | mixed | 0 | 1 | — | lock-order deadlock (non-corrupting, deferred); registry guard, recovery race — all fixed |

**ADR-021 claim-pull re-implementation & review.** The execution layer was re-added as the claim-pull
model: `PipelineScheduler`/`PipelineClaimer`/`PipelineWorker`/`StepRunner`/`StepReporter`, lease+UUID-token
fencing, soft caps (running-pipeline + terraform-slot), and guarded write-back replacing the `finish()`
CAS. `PipelineEngine` and `PipelineRepository.finish` were removed; `PipelineEngineTest` /
`PipelineEngineTransactionTest` folded into `PipelineExecutionTest`.

**Clean-code / exception / naming campaign (owner-directed).** De-abbreviated injected fields;
`orElse(null)` → `Optional`; DTO `new` → Lombok `@Builder`; terraform-slot gate generalized to a
`TaskType.consumesTerraformSlot()` property (not a hardcoded `NAME`); `tx1/tx2`/`report` vocabulary →
`claim`/`run`/`writeBack`; named-predicate extraction for opaque conditionals; API exceptions given
HTTP status + stable `ORCHESTRATION_*` codes via an `OrchestrationErrorCode` enum
(`MissingPipelineIdException` 400 / `PipelineNotFoundException` 404 / `PipelineAlreadyActiveException` 409 /
`PipelinePersistenceException` 500), `GlobalAdvice` moved to `advice/`.

**ADR-016 §4 reversal (D-C).** Duplicate create now → 409 Conflict instead of returning the existing
run (criteria D2/D3 updated above). Single-owner is still guaranteed by the `active_target` unique
constraint, independent of create's return behavior.

Two full codex+opus review campaigns over the claim-pull delta and the §4-reversal delta returned
MERGE-READY (no P0/P1). PR #8 squash-merged to `main`.

**Status: DONE (ADR-016 domain + ADR-021 execution).** Suite green. Every criterion above is ✅ or a
documented 📦.
