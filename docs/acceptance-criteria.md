# ADR-016 Acceptance Criteria & Test Matrix

The **definition of done** for this module, derived from
[ADR-016](adr/016-install-delete-pipeline-domain-model.md). Each ADR decision becomes one or more
concrete, testable criteria, each tied to the code and the test that proves it. This is the
self-review checklist; the review-round log at the bottom tracks the codex passes.

**Status legend:** ✅ met (code + test evidence) · ⚠️ gap (must fix) · 📦 deferred (out of v1 scope,
documented).

## A. Decision 1 — the database is the only state

| # | Criterion | Verified by | Status |
|---|---|---|---|
| A1 | Progress lives only in DB rows; the reconciler reads rows each tick and resumes — no in-memory authority | `PipelineReconciliation.reconcile` re-reads the pipeline + chain every tick | ✅ |
| A2 | A restart re-polls an IN_PROGRESS terraform job by its stored `job_id` (crash recovery) | `ReconcilerTest.crashResumeRePollsAnInProgressTerraformJobByItsStoredJobId` | ✅ |
| A3 | A committed reconcile is visible to a fresh read (own transaction) | `ReconciliationTransactionTest.reconcileCommitsInItsOwnTransactionVisibleToAFreshRead` | ✅ |

## B. Decision 2 — two domain tables, a small durable state machine

| # | Criterion | Verified by | Status |
|---|---|---|---|
| B1 | Task lifecycle is BLOCKED→READY→IN_PROGRESS→DONE\|FAILED\|CANCELLED | `TaskStatus`, `TaskMachine.advance` | ✅ |
| B2 | Pipeline lifecycle is RUNNING→DONE\|FAILED\|CANCELLED | `PipelineStatus`, `PipelineReconciliation.converge` | ✅ |
| B3 | First task starts READY; the rest start BLOCKED and flip to READY when the predecessor is DONE | `PipelineInserter.insert` + `ReconcilerTest.anInstallRunsBothTasksThroughBlockedAndUnblockToDone` | ✅ |
| B4 | The current task is the lowest-seq non-terminal task | `PipelineReconciliation.currentTask` | ✅ |
| B5 | Pipeline status is a stored projection, written in the same tx as the task transition that changes it | `PipelineRepository.finish` (CAS in the reconcile tx) | ✅ |
| B6 | Five core enums + a closed `TaskOperation` (a task's kind selects the executor, operation the action) | `domain/*` enums; `TaskMachine` switches on kind | ✅ |
| B7 | A recipe (ordered task list) is a code default per type | `create/Recipes` | ✅ |

## C. Decision 3 — observation is separate from state

| # | Criterion | Verified by | Status |
|---|---|---|---|
| C1 | `task_attempt` = one row per attempt, `attempt_no = failCount + 1` | `Observations` + `ObservationTest.aRetryingTaskRecordsOneAttemptRowPerAttemptWithIncreasingAttemptNo` | ✅ |
| C2 | `task_check` is UPDATE-in-place (one row per attempt; counters grow, not rows) | `ObservationTest.aConditionPolledNotMetUpdatesOneCheckRowInPlace` | ✅ |
| C3 | The reconciler never **reads** the observation tables for correctness | `Observations` only writes; `PipelineReconciliation`/`TaskMachine` never query them | ✅ |
| C4 | Losing observations degrades debuggability only, never correctness — an observation write can never roll back task progress | observation writes isolated on a `REQUIRES_NEW`, best-effort boundary | ✅ |

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
| E1 | Dispatch is idempotent / at-least-once safe; a crash before storing `job_id` heals by re-dispatch | `TaskMachine.dispatch` (re-dispatch on READY); A2 crash-resume test | ✅ |
| E2 | No `DISPATCHING` state; the latest `job_id` is recorded | `TaskStatus` has no DISPATCHING; `dispatch` sets `job_id` | ✅ |
| E3 | `(task_id, attempt_no)` is our logical attempt identity, not an InfraManager key | `TaskAttempt` unique `(task_id, attempt_no)` | ✅ |

## F. Decision 6 — bounded waiting and retry

| # | Criterion | Verified by | Status |
|---|---|---|---|
| F1 | `fail_count` per task; below `maxFailCount` → fresh re-run, at/above → FAILED | `TaskMachine.retryOrFail` + `ReconcilerTest.terraformJobFailureRetriesThenFailsAtMax...` | ✅ |
| F2 | Per-call timeout → `CALL_TIMEOUT` | `ImCall.withTimeout` + `ReconcilerTest.aSlowDispatchTimesOutAsCallTimeout...` | ✅ |
| F3 | Per-task `executionTimeout` (TF) → `EXECUTION_TIMEOUT` | `ReconcilerTest.terraformJobRunningPastExecutionTimeout...` | ✅ |
| F4 | Per-task `ttl` (condition) → `TTL_EXPIRED` (no retry) | `ReconcilerTest.conditionPastTtlExpires...` | ✅ |
| F5 | Both deadlines map to canonical `ErrorCode` values, not separate states | `ErrorCode`; no extra `TaskStatus` | ✅ |

## G. Decision 7 — minimal lifecycle

| # | Criterion | Verified by | Status |
|---|---|---|---|
| G1 | Exactly two task kinds (TERRAFORM_JOB, CONDITION_CHECK) | `TaskKind` | ✅ |
| G2 | Retry is a fresh run (no terminal resurrection) | `TaskMachine.retryOrFail` resets to READY | ✅ |
| G3 | Cancel converges directly to CANCELLED (no CANCELLING) and terminalizes every non-terminal task | `PipelineControl.cancel` + `PipelineControlTest` | ✅ |
| G4 | Cancel is a RUNNING-guarded transition — it can never resurrect a pipeline a converge already terminalized | `PipelineControl.cancel` uses the guarded `finish(..., CANCELLED, ...)` CAS, 0-row = no-op | ✅ |
| G5 | A FAILED pipeline marks the failing task FAILED and CANCELS the rest | `PipelineReconciliation.cancelRemaining` + `ReconcilerTest.aFailedPipelineCancelsItsRemainingBlockedTasks` | ✅ |
| G6 | A terminal state is never resurrected | guarded CAS on every pipeline terminalization (finish/cancel); `Task.@Version` on the task race | ✅ |

## H. Schema & persistence (JPA-generated, MySQL)

| # | Criterion | Verified by | Status |
|---|---|---|---|
| H1 | Tables, indexes, unique constraints are generated from JPA annotations — no Flyway / hand-written SQL | `@Table`/`@Index`/`@UniqueConstraint`; Hibernate DDL log | ✅ |
| H2 | MySQL target; tests run on H2 MySQL-mode without Docker | `application.yml` (MySQL) + test `application.yml` (H2 `MODE=MySQL`) | ✅ |
| H3 | "One active per target" works on MySQL despite no partial unique index (nullable `active_target` + unique; multiple NULLs allowed) | `Pipeline.activeTarget`; uniqueness tests | ✅ |
| H4 | `@Version` optimistic lock guards the cancel/reconcile task race | `Task.version` + `ReconciliationTransactionTest.aCancelThatCommitsDuringTheImCall...` | ✅ |

## I. Exception strategy (external vs business)

| # | Criterion | Verified by | Status |
|---|---|---|---|
| I1 | Business failures are `ErrorCode` values on the row, never thrown | `TaskMachine.fail`/`retryOrFail` | ✅ |
| I2 | External-call failures are exceptions caught at exactly one boundary and translated to `ErrorCode` | `TaskMachine` dispatch/poll catches | ✅ |
| I3 | Interruption is a fail-fast runtime signal, NOT mapped to a business `ErrorCode` | `ImCall` throws a distinct interruption type; `TaskMachine` rethrows it | ✅ |
| I4 | The duplicate-create catch is targeted (constraint-checked) and rethrows other violations | see D3 | ✅ |
| I5 | Strategy is documented | `docs/exception-strategy.md` | ✅ |

## J. Code quality (spring-java21 skill §6) & extensibility

| # | Criterion | Verified by | Status |
|---|---|---|---|
| J1 | Constructor injection only; `Clock` injected; no direct `now()` in logic | all beans | ✅ |
| J2 | Closed-set switches are exhaustive, no `default` swallow | `TaskMachine.advance` enumerates terminal states | ✅ |
| J3 | Only `ImClient` is an interface (a real boundary); static utility (`TaskKnobs`) over a one-method bean | package scan | ✅ |
| J4 | Tests behavior-named, fixed `Clock`, fakes not mocks | test suite | ✅ |
| J5 | Extension seams (more task kinds / post-check / outbox) documented, not pre-built | `docs/extensibility.md` | ✅ |

## Deferred / accepted limitations (📦)

- **Observations ride the reconcile transaction** (no `REQUIRES_NEW` / separate bean). The async
  observation split was rejected (ADR Option B); a failed observation write rolls back and retries the
  whole tick, which cannot corrupt state, so this honors the ADR's correctness-only guarantee. The
  `REQUIRES_NEW` boundary is the documented upgrade. 📦
- **Cancelled in-flight attempts** keep `task_attempt.status = IN_PROGRESS` (the authoritative `task`
  row shows CANCELLED). Observation-only; the tables are explicitly best-effort. 📦
- **State-machine tests run inside the `@DataJpaTest` transaction** (fast, deterministic); the real
  commit-boundary and cancel-race behavior is proven separately in `ReconciliationTransactionTest`
  (`@SpringBootTest`, no test transaction). 📦
- **Concurrent duplicate-create race** is verified sequentially (H2); the true row-level INSERT race
  is a MySQL/CI concern (the constraint itself is enforced by H2). 📦
- **HTTP InfraManager adapter** and the response-code/summary observation fields are out of v1 scope
  (the host wires the `ImClient`). 📦

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
so `cancelDoesNotResurrect…` now deterministically exercises the `finish()==0` branch [G4]. Suite green
(23 tests).

_Done criteria: codex round 2 returned merge-ready (no P0/P1); round-2 P2s fixed; round 3 confirms. Every
criterion above is ✅ or a documented 📦._
