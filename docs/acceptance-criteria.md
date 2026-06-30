# ADR-016 Acceptance Criteria & Test Matrix

The **definition of done** for this module, derived from
[ADR-016](adr/016-install-delete-pipeline-domain-model.md). Each ADR decision becomes one or more
concrete, testable criteria, each tied to the code and the test that proves it. This is the
self-review checklist; the review-round log at the bottom tracks the codex passes.

> **Scope.** This module now implements **both** the ADR-016 domain model (durable state, data model,
> uniqueness rule, failure semantics, lifecycle) **and** the ADR-021 claim-pull execution layer (claim
> mechanism, two-transaction split, per-call timeout decorator, cancel Case A/B, and the sweep
> scheduler). The criteria below test the domain (sections A–J) and the execution model (section K).
> Domain tests drive `PipelineWorker.pollOnce()` directly; execution tests exercise the claim-pull
> cycle end-to-end.

**Status legend:** ✅ met (code + test evidence) · ⚠️ gap (must fix) · 📦 deferred (out of v1 scope,
documented).

## A. Decision 1 — the database is the only state

| # | Criterion | Verified by | Status |
|---|---|---|---|
| A1 | Progress lives only in DB rows; each claim-pull cycle re-reads the rows and resumes — no in-memory authority | `StepReporter.report` re-reads the pipeline row under `FOR UPDATE` and the task chain on every tx2; `PipelineWorker.loadStepContext` re-reads on every `pollOnce` call | ✅ |
| A2 | A restart re-polls an IN_PROGRESS terraform job by its stored `job_id` (crash recovery) | `PipelineEngineTest.crashResumeRePollsAnInProgressTerraformJobByItsStoredJobId` | ✅ |
| A3 | A committed tx2 (report) is visible to a fresh read (own transaction) | `PipelineEngineTransactionTest.advanceCommitsInItsOwnTransactionVisibleToAFreshRead` (uses `worker.pollOnce()`) | ✅ |

## B. Decision 2 — two domain tables, a small durable state machine

| # | Criterion | Verified by | Status |
|---|---|---|---|
| B1 | Task lifecycle is BLOCKED→READY→IN_PROGRESS→DONE\|FAILED\|CANCELLED | `TaskStatus`; `TaskMachine.applyOutcome` (driven by `StepRunner.runStep` phase A) | ✅ |
| B2 | Pipeline lifecycle is RUNNING→DONE\|FAILED\|CANCELLED | `PipelineStatus`, `StepReporter.converge` | ✅ |
| B3 | First task starts READY; the rest start BLOCKED and flip to READY when the predecessor is DONE | `PipelineInserter.insert` + `PipelineEngineTest.anInstallRunsBothTasksThroughBlockedAndUnblockToDone` | ✅ |
| B4 | The current task is the lowest-sequence non-terminal task | `StepReporter.currentTask` (private) / `PipelineWorker.loadStepContext` | ✅ |
| B5 | Pipeline status is a stored projection, written in the same tx as the task transition that changes it | `StepReporter.converge`/`terminalize` (tx2, dirty-check on the FOR-UPDATE-locked managed entity — no CAS) | ✅ |
| B6 | Five core enums + a closed `TaskOperation` (a task's type selects the executor, operation the action) | `entity/*` enums; the type is resolved by name (no `switch` on kind) | ✅ |
| B7 | A recipe (ordered task list) is a code default per type | `Recipes` | ✅ |
| B8 | A task's behaviour is a `TaskType` resolved **by name** through `TaskTypeRegistry`; a new kind self-registers (no enum/`switch` edit) | `TaskType`/`TaskTypeRegistry`; `TerraformTask`/`ConditionCheckTask` | ✅ |

## C. Decision 3 — observation is separate from state

| # | Criterion | Verified by | Status |
|---|---|---|---|
| C1 | `task_attempt` = one row per attempt, `attempt_no = failCount + 1` | `Observations` + `ObservationTest.aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo` | ✅ |
| C2 | `task_check` is UPDATE-in-place (one row per attempt; counters grow, not rows) | `ObservationTest.aConditionPolledNotMetUpdatesOneCheckRowInPlace` | ✅ |
| C3 | The engine never **reads** the observation tables for correctness | `Observations` only writes; `StepReporter`/`TaskMachine` never query them | ✅ |
| C4 | Losing observations degrades debuggability only, never correctness — they can never roll back task progress | observations ride tx2 (`StepReporter.report`); a failed write rolls back tx2 and the claim-pull cycle retries on the next claim, which cannot corrupt the atomic task row — see Deferred | ✅ |

## D. Decision 4 — one active pipeline per target

| # | Criterion | Verified by | Status |
|---|---|---|---|
| D1 | At most one non-terminal pipeline per target (JPA unique constraint on `active_target`) | `Pipeline` `@UniqueConstraint`; `PipelineUniquenessTest.duplicateCreate...` | ✅ |
| D2 | A duplicate create — of any type — returns the existing active run, not an error | `PipelineCreator.create` + `PipelineUniquenessTest.aDifferentTypeCreate...` | ✅ |
| D3 | Only the `active_target` unique violation triggers recovery; other integrity violations propagate | `PipelineCreator` checks the constraint name before recovering | ✅ |
| D4 | A target is reusable once its prior run is terminal (`active_target` cleared on every terminalization) | `finish`/`cancel` clear it; `PipelineUniquenessTest.aNewRunIsAllowed...` | ✅ |

## E. Decision 5 — correctness rests on idempotency, not exactly-once

| # | Criterion | Verified by | Status |
|---|---|---|---|
| E1 | Dispatch is idempotent / at-least-once safe; a crash before storing `job_id` heals by re-dispatch | `StepRunner.dispatch` (re-dispatch on READY); `TaskMachine.markInProgress` applies the `Dispatched` outcome in tx2; A2 crash-resume test | ✅ |
| E2 | No `DISPATCHING` state; the latest `job_id` is recorded | `TaskStatus` has no DISPATCHING; `dispatch` sets `job_id` | ✅ |
| E3 | `(task_id, attempt_no)` is our logical attempt identity, not an InfraManager key | `TaskAttempt` unique `(task_id, attempt_no)` | ✅ |

## F. Decision 6 — bounded waiting and retry

| # | Criterion | Verified by | Status |
|---|---|---|---|
| F1 | `fail_count` per task; below `maxFailCount` → fresh re-run, at/above → FAILED | `TaskMachine.retryOrFail` + `PipelineEngineTest.terraformJobFailureRetriesThenFailsAtMaxAndFailsThePipeline` | ✅ |
| F2 | Per-call timeout → `CALL_TIMEOUT` (`TimeBoundedInfraManagerClient` raises `CallTimeoutException`; `StepRunner.runStep` translates it to `StepOutcome.callTimeout`; `TaskMachine.applyOutcome` applies `CALL_TIMEOUT` in tx2) | `PipelineEngineTest.aCallTimeoutFromTheInfraManagerClientIsCallTimeoutAndRetries` | ✅ |
| F3 | Per-task `executionTimeout` (TF) → `EXECUTION_TIMEOUT` | `PipelineEngineTest.terraformJobRunningPastExecutionTimeoutFailsWithExecutionTimeout` | ✅ |
| F4 | Per-task `timeToLive` (condition) → `TIME_TO_LIVE_EXPIRED` (no retry) | `PipelineEngineTest.conditionPastTimeToLiveExpiresToFailedWithTimeToLiveExpired` | ✅ |
| F5 | Both deadlines map to canonical `ErrorCode` values, not separate states | `ErrorCode`; no extra `TaskStatus` | ✅ |

## G. Decision 7 — minimal lifecycle

| # | Criterion | Verified by | Status |
|---|---|---|---|
| G1 | Two task kinds today (TERRAFORM_JOB, CONDITION_CHECK); the set is open via new `TaskType` impls | `TerraformTask`/`ConditionCheckTask` (a third kind is a new file, not an enum edit) | ✅ |
| G2 | Retry is a fresh run (no terminal resurrection) | `TaskMachine.retryOrFail` resets to READY | ✅ |
| G3 | Cancel converges directly to CANCELLED (no CANCELLING) and terminalizes every non-terminal task | `PipelineControl.cancel` + `PipelineControlTest` | ✅ |
| G4 | Cancel is a RUNNING-guarded transition — it can never resurrect a pipeline a converge already terminalized | `PipelineControl.cancel`: Case A uses `cancelIfIdle` (RUNNING + idle guard, 0-row = no-op); Case B `requestCancel` also RUNNING-guarded | ✅ |
| G5 | A FAILED pipeline marks the failing task FAILED and CANCELS the rest | `TaskCanceller.cancelNonTerminal` called from `StepReporter.converge` + `PipelineEngineTest.aFailedPipelineCancelsItsRemainingBlockedTasks` | ✅ |
| G6 | A terminal state is never resurrected | ownership-guarded write-back (tx2 `findByIdForUpdate` + Java `claimToken.equals(pipeline.getClaimedBy())` no-op guard); cancel Case A/B both RUNNING-guarded; `Task.@Version` as backstop | ✅ |
| G7 | A task whose stored `taskName` resolves to no `TaskType` fails with `UNKNOWN_TASK` (a value, no NPE) | `TaskMachine` resolve→`fail(UNKNOWN_TASK)` + `PipelineEngineTest.aTaskWhoseStoredNameHasNoRegisteredTypeFailsAsUnknownTask` | ✅ |

## H. Schema & persistence (JPA-generated, MySQL)

| # | Criterion | Verified by | Status |
|---|---|---|---|
| H1 | Tables, indexes, unique constraints are generated from JPA annotations — no Flyway / hand-written SQL | `@Table`/`@Index`/`@UniqueConstraint`; Hibernate DDL log | ✅ |
| H2 | MySQL target; tests run on H2 MySQL-mode without Docker | `application.yml` (MySQL) + test `application.yml` (H2 `MODE=MySQL`) | ✅ |
| H3 | "One active per target" works on MySQL despite no partial unique index (nullable `active_target` + unique; multiple NULLs allowed) | `Pipeline.activeTarget`; uniqueness tests | ✅ |
| H4 | The cancel-vs-worker race cannot resurrect a terminal state | claim fencing (ownership-guarded tx2 write-back + cancel Case A/B) is the primary guard; `Task.@Version` is a backstop; `PipelineEngineTransactionTest.aCancelThatCommitsDuringTheInfraManagerClientCallDoesNotClobberCancelled` proves the Case B cooperative cancel path | ✅ |
| H5 | Indexes are JPA-declared and cover the actual queries; no orphan index (the status-scan index was dropped with the reconciler scan) | unique constraints `uq_task_pipeline_sequence`/`uq_task_attempt`/`uq_task_check_attempt`/`uq_pipeline_active_target` back uniqueness queries; non-unique indexes `idx_pipeline_claim (status, next_due_at)` (claim scan), `idx_pipeline_claimed_until (claimed_until)` (admission gate), and `idx_task_name_status (task_name, status)` (TF slot gate) cover the execution queries | ✅ |

## I. Exception strategy (external vs business)

| # | Criterion | Verified by | Status |
|---|---|---|---|
| I1 | Business failures are `ErrorCode` values on the row, never thrown | `TaskMachine.fail`/`retryOrFail` | ✅ |
| I2 | External-call failures are exceptions caught at exactly one boundary and translated to a `StepOutcome` → `ErrorCode` | `StepRunner.runStep` (wrapping execute/check/postCheck) + `PipelineEngineTest.aThrowingDispatchIsTreatedAsCheckErrorAndRetriedThenFailed` (+ the CALL_TIMEOUT and postCheck tests) | ✅ |
| I3 | Interruption is a fail-fast runtime signal, NOT mapped to a business `ErrorCode` | `InfraManagerClient.CallInterruptedException`; `StepRunner.runStep` catches only `CallTimeout`/`CallFailed`, so an interrupt is **not caught** — it propagates out of `StepRunner.runStep` and is absorbed by `PipelineScheduler.drain`'s per-pipeline shutdown check (restores interrupt, aborts sweep) | ✅ |
| I4 | The duplicate-create catch is targeted (constraint-checked) and rethrows other violations | see D3 | ✅ |
| I5 | Strategy is documented | `docs/exception-strategy.md` | ✅ |
| I6 | A null/blank job id or null poll from the boundary is guarded → `CHECK_ERROR` retry, never an NPE or a persisted null handle | `TerraformTask` guards + `PipelineEngineTest.aDispatchReturningNoJobIdIsTreatedAsCheckErrorAndRetried` | ✅ |

## J. Code quality (spring-java21 skill §6) & extensibility

| # | Criterion | Verified by | Status |
|---|---|---|---|
| J1 | Constructor injection only; `Clock` injected; no direct `now()` in logic | all beans | ✅ |
| J2 | Closed-set switches are exhaustive, no `default` swallow | `TaskMachine.applyOutcome` uses a sealed `StepOutcome` switch; `Recipes.forType` is compile-exhaustive | ✅ |
| J3 | Interfaces are justified — `InfraManagerClient` (external boundary, prod + fake) and `TaskType` (genuine N-impl polymorphism, registry-resolved); static utility (`TaskSettings`) over a one-method bean | package scan | ✅ |
| J4 | Tests behavior-named, fixed `Clock`, fakes not mocks | test suite | ✅ |
| J5 | Extension seams (more task kinds / post-check / outbox) documented, not pre-built | `docs/extensibility.md` | ✅ |

## K. ADR-021 — claim-pull execution model

| # | Criterion | Verified by | Status |
|---|---|---|---|
| K1 | tx1 claim stamps a fresh per-claim UUID fencing token + lease, and blocks a second concurrent claim | `PipelineClaimer.claimOneDue` mints `UUID.randomUUID()` + sets `claimed_by`/`claimed_until`; `PipelineExecutionTest.claimStampsAFreshFencingTokenAndLeaseAndBlocksASecondClaim` | ✅ |
| K2 | Crash recovery: an expired lease is reclaimed with a different fencing token | `PipelineExecutionTest.anExpiredLeaseIsReclaimedWithADifferentFencingToken` | ✅ |
| K3 | No stale clobber: a stale claim token cannot overwrite state after reclaim | `PipelineExecutionTest.aStaleClaimTokenCannotClobberStateAfterReclaim` | ✅ |
| K4 | Two-transaction split: external call runs outside any transaction (between tx1 and tx2) | `StepRunner` has no `@Transactional`; `PipelineWorker.process` ordering (claim → runStep → report); `PipelineEngineTransactionTest` (real-tx proof via `advanceCommitsInItsOwnTransactionVisibleToAFreshRead`) | ✅ |
| K5 | Cancel Case A (idle pipeline): API path terminates immediately and clears the claim | `PipelineExecutionTest.cancelCaseAIdleTerminatesImmediatelyAndClearsTheClaim` | ✅ |
| K6 | Cancel Case B (live lease): API raises flag; claim-holder terminalizes under the row lock | `PipelineExecutionTest.cancelCaseBLiveLeaseRaisesTheFlagAndTheClaimHolderTerminalizesUnderTheLock` | ✅ |
| K7 | Soft admission cap blocks a second concurrent claim when `runningPipelineCap` is reached | `PipelineSoftCapTest.theAdmissionCapBlocksASecondConcurrentClaim` | ✅ |
| K8 | TF slot gate reschedules and releases the claim when no slot is free | `PipelineSoftCapTest.theTerraformSlotGateReschedulesAndReleasesTheClaimWhenNoSlotIsFree` | ✅ |
| K9 | Per-call timeout is owned by `TimeBoundedInfraManagerClient` decorator (`@Primary`) | `client/TimeBoundedInfraManagerClient.java`; `TimeoutException → CallTimeoutException`; `PipelineEngineTest.aCallTimeoutFromTheInfraManagerClientIsCallTimeoutAndRetries` | ✅ |
| K10 | Per-pipeline isolation: a single-pipeline failure does not abort the sweep | `PipelineScheduler.drain` per-pipeline `catch (RuntimeException)` logs + skips; `CallInterruptedException` aborts the sweep | ✅ |

## Deferred / accepted limitations (📦)

- **Observations ride tx2 (`StepReporter.report`)** (no `REQUIRES_NEW` / separate bean). The
  async observation split was rejected (ADR Option B); a failed observation write rolls back tx2 and
  the claim-pull cycle retries on the next claim (cannot corrupt state), so this honors the ADR's
  correctness-only guarantee. The `REQUIRES_NEW` boundary is the documented upgrade. 📦
- **Cancelled in-flight attempts** are ended as `task_attempt.status = CANCELLED` by `TaskCanceller`
  (which ends the open attempt before terminalizing the task), so the observation history matches the
  authoritative `task` row rather than leaving a stale `IN_PROGRESS` attempt behind a terminal task. ✅
- **State-machine tests suppress the `@DataJpaTest` test transaction** with
  `@Transactional(propagation = NOT_SUPPORTED)` so each `worker.pollOnce()` call commits tx1 and tx2
  independently (like production); the real commit-boundary and cancel-race behavior is additionally
  proven in `PipelineEngineTransactionTest` (`@SpringBootTest`, real threads/latches). 📦
- **Concurrent duplicate-create race** is verified sequentially (H2); the true row-level INSERT race
  is a MySQL/CI concern (the constraint itself is enforced by H2). 📦
- **HTTP InfraManager adapter** and the response-code/summary observation fields are out of v1 scope
  (the host wires the `InfraManagerClient`). 📦
- **Cancel vs concurrent-worker lock order** (codex R4 P1, now partly addressed). The claim-pull
  model serializes all pipeline work through the row-level lease: only one worker holds the claim at a
  time, so two drivers can never concurrently advance the same pipeline — the `FOR UPDATE SKIP LOCKED`
  claim is the serialization primitive. The cancel-vs-worker race is handled by cancel Case A/B (one
  status writer per case) + the tx2 ownership guard (stale straggler no-ops). A residual MySQL/InnoDB
  deadlock between a cancel and a concurrent claim-scan under high contention remains a CI concern
  (H2 tests do not reproduce InnoDB deadlocks); it would abort one transaction, which is safe
  (`cancelIfIdle` is RUNNING-guarded + idempotent; the worker re-polls on the next sweep). 📦

## Review-round log

| Round | Reviewer | Verdict | P0 | P1 | P2 | Notes |
|---|---|---|---|---|---|---|
| 1 | codex (gpt-5.5 xhigh) | No | 0 | 4 | 3 | cancel CAS, observation isolation, dup-create catch, interrupt mapping; nits: default arm, cancelled-attempt status, @DataJpaTest tx |
| 1 | opus (code-reviewer) | No | 0 | 3 | 8 | **corroborated cancel CAS as the one blocker**; dup-create catch; @DataJpaTest tx (rated P1); nits incl. TTL-per-attempt, converge re-query, observation tx |

**Round-1 fixes applied** (commit `fix:` …): cancel via RUNNING-guarded `finish()` CAS [G4]; targeted
dup-create catch [D3/I4]; `NOT_SUPPORTED` on the state-machine tests [test integrity]; interrupt
rethrown as fail-fast [I3]; exhaustive `advance` switch [J2]; cancel-no-clobber test added; observation
same-tx model documented [C4]; TTL-per-attempt comment. Suite green (23 tests).

| 2 | codex (gpt-5.5 xhigh) | **Yes** | 0 | 0 | 2 | all 5 round-1 fixes VERIFIED; 2 new P2s: interrupt still caught by the tick loop, and the cancel test hit the early guard not the CAS 0-row branch |

**Round-2 fixes applied** (commit `fix:` …): `Reconciler.tick` now rethrows `CallInterruptedException`
before the per-pipeline `RuntimeException` catch, so a shutdown interrupt truly aborts the tick [I3];
`PipelineControl.cancel` made the `finish()` CAS its **sole** guard (dropped the early terminal return),
so `cancelDoesNotResurrect…` now deterministically exercises the `finish()==0` branch [G4]. Added
`ImCallTest` (timeout / interrupt / success boundary). Suite green (26 tests).

| 3 | codex (gpt-5.5 xhigh) | **Yes** | 0 | 0 | 0 | both round-2 fixes VERIFIED; **no remaining findings** |

Rounds 1–3 reviewed the pre-refactor tree (26 tests), which still carried an ADR-021 **execution layer**
(`Reconciler` tick loop, `ReconcileScheduler`/`@Scheduled`, the `ImCall` per-call-timeout wrapper, and
the worker-pool settings).

**Post-review refactor — pure domain (ADR-016 only).** The execution layer was stripped so this module
is exactly PR #511's *domain half*: `Reconciler`, `ReconcileScheduler`, and `ImCall` deleted;
`@Scheduled`/worker-pool settings removed; the boundary timeout exceptions moved into the InfraManager client
(`CallTimeoutException`/`CallInterruptedException`); the `reconcile` package renamed to `engine` and
`PipelineReconciliation.reconcile(id)` to `PipelineEngine.advance(id)`. Behaviour is unchanged — the
state machine, creation/cancel, and observation are identical; only the *driver* (the ADR-021 runner)
left the repo. Tests drive `advance()` directly. `ImCallTest` (3 tests for the deleted wrapper) was
removed → **23 tests**. The criteria/evidence above are updated to the post-refactor names.

| 4 | codex (gpt-5.5 xhigh) | **Yes** | 0 | 0 | 0 | re-review of the pure-domain tree: refactor preserved every invariant; no findings |

**Post-review redesign — `TaskType` strategy + layered packages.** Per the owner: the `TaskKind` enum +
`switch` became a **`TaskType` interface** (`taskName`/`execute`/`check`(/`postCheck`)→`TaskProgress`) with
`TerraformTask`/`ConditionCheckTask` resolved by name via `TaskTypeRegistry`; an unknown name →
`UNKNOWN_TASK` [B8/G7]. `Task.kind` → `Task.taskName`. Code repackaged into layers; `ImClient` →
`InfraManagerClient`; `PipelineInserter` for-loop → `IntStream`; added `GlobalAdvice`. API-boundary null
guards added [I6]. The orphan `ix_pipeline_status` index was dropped [H5]. Suite **25 tests**.

| 5 | codex (gpt-5.5 xhigh) | No | 0 | 1 | 4 | lock-order deadlock (P1); dup-create recovery race + unindexed query; registry null/dup names; unknown-task fail did not end the in-flight attempt |
| 5 | opus (code-reviewer) | **Yes** | 0 | 0 | 4 | APPROVE — concurrency/exception/NPE all sound; P2 docs: ADR enum drift, registry guard, chain re-read, lastActivityAt |
| 5 | sonnet (code-reviewer) | No | 0 | 3 | 5 | unindexed recovery query, ADR enum drift, GlobalAdvice swallows the cause; suggestions: Recipes switch, converge re-read, test try-finally |

**Round-5 fixes applied:** recovery now queries the indexed `findByActiveTarget` with a bounded retry on
the terminalize-between race [D2/D3]; `TaskTypeRegistry` rejects null/blank/duplicate names at boot;
the unknown-task failure path (`failUnknownTask`) ends the in-flight attempt; `GlobalAdvice` logs the cause; `Recipes.forType` is a
compile-exhaustive `switch`; `converge` reuses the loaded chain (no re-read); cancel/cascade loops →
streams; the transaction test releases the gated poll in `finally`; the ADR enum table notes
TaskType/`UNKNOWN_TASK`. The lone P1 (lock-order deadlock) is **non-corrupting** and dispositioned as an
ADR-021 concern (see Deferred) — opus and sonnet did not consider it merge-blocking. Suite green (25 tests).

**Post-review structure pass (owner-directed).** The final layout is
`entity / enums / dto / model / service / client / controller / repository / utils` (+ root app wiring
`PipelineApplication`/`PipelineConfig`/`PipelineSettings`): all enums in `enums` (incl. `CheckSignal`);
external transport values in `dto`; non-bean domain value/contract types (`TaskType`, `TaskProgress`,
`Recipe`, `RecipeStep`) in `model`; `service` holds **only** `@Component`/`@Service` beans; `advice` →
`controller`. Removed every identifier abbreviation: `seq` → `sequence`, `ttl` → `timeToLive`
(`ErrorCode.TTL_EXPIRED` → `TIME_TO_LIVE_EXPIRED`, yml `pipeline.time-to-live`), `cve` →
`constraintViolation`, catch `e` → `exception`, `Throwable t` → `cause`. Suite green (25 tests).

**Status: DONE.** Across three independent reviewers the implementation is merge-ready: opus APPROVE with
no P0/P1; codex and sonnet's actionable findings all fixed; the single P1 (concurrent-driver lock-order)
is a documented, non-corrupting ADR-021 liveness concern. Suite green (25 tests); every criterion above is
✅ or a documented 📦. The module was the ADR-016 domain half at this review stage; execution (ADR-021) was out of scope here and was subsequently implemented (see Section K above).

## Clean-code & exception-handling review campaign (owner-directed)

A focused readability + exception campaign over the final code: **21 independent reviews across 4 rounds**
(codex gpt-5.5 xhigh ×4 + opus ×17), fixing between rounds.

| Round | Reviews | Lens | Outcome |
|---|---|---|---|
| 1 | 4 | broad readability + exception sweep | DRY/naming/magic-literal findings; exception-narrowing case raised |
| 2 | 6 | exception/postCheck, service per-method, Korean-comment clarity, naming/structure/JPA, model/dto/enums, codex | **P1: postCheck sat outside the exception boundary** (codex+opus) → fixed |
| 3 | 6 | TaskMachine correctness, service fixes, test quality, docs accuracy, holistic, codex | **P1: constraint-name literal duplicated** → single-sourced; under-asserting tests strengthened |
| 4 | 5 | adversarial correctness, holistic readability, docs+Korean consistency, test suite, codex | **0 P0 / 0 P1 — MERGE-READY** (codex "yes"); only P2 polish |

**What the campaign changed.** (1) The InfraManager boundary was **narrowed** to a closed vocabulary —
`CallTimeoutException` / `CallFailedException` / `CallInterruptedException` — and unified into one
`TaskMachine.runExternalCall` helper wrapping `execute`/`check`/`postCheck`; it catches only Timeout/Failed
(logs, translates), while an interrupt and any other `RuntimeException` (a genuine bug) propagate fail-fast
instead of being mis-recorded as `CHECK_ERROR`. Every throw/catch site is catalogued in
[exception-cases.md](exception-cases.md). (2) Cancel now ends the open attempt as `CANCELLED` via the shared
`TaskCanceller` (DRY); `advance` fails fast on a missing pipeline id. (3) The `TaskType` lifecycle gained the
`postCheck` extension seam, inside the boundary and test-covered. (4) Packages were split to
`entity/enums/dto/model/service/...` (`service` = beans only); identifiers de-abbreviated
(`execute`, `attemptNumber`, `TaskSettings`, `RecipeStep`, `failedRetryable`/`failedTerminal`,
`failUnknownTask`); config defaults set to **executionTimeout 50m / maxFailCount 2**; and every major class
carries a **detailed Korean class-header Javadoc**. Suite green (**30 tests**, incl. the postCheck seam +
ErrorCode-discriminating retry + poll-phase timeout-observation tests).

## ADR-021 review campaign

Rounds tracked separately (to be filled by the orchestrator after S7 merges).

| Round | Reviewer | Verdict | P0 | P1 | P2 | Notes |
|---|---|---|---|---|---|---|
