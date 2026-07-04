# ADR-021: Install/Delete Pipeline — Claim-Pull Execution Model

## Status

Proposed — 2026-06-27 (revised 2026-07-01: condition rebased from `ttl` to a retry count;
revised 2026-07-03: `PENDING` start-delay wait state, `PENDING → RUNNING` at claim — LIN-30;
revised 2026-07-03 (alignment pass): index/SQL wording matched to the MySQL 8 implementation,
`active_target` slot release documented in the cancel and tx2 paths;
revised 2026-07-04 (alignment pass 2, codex round 1): `task_check` is 0..1 per attempt (not one
per attempt); no distinct 429/503 backpressure in V1; lease bound covers the multi-call Terraform
step; backoff is ×2 geometric and jitter desynchronizes pods; metrics catalog is not yet
instrumented; see Revision history).

This is the **execution half** of the install/delete pipeline design: how the durable state
machine of [ADR-016](016-install-delete-pipeline-domain-model.md) is actually driven forward.
The domain model (tables, states, uniqueness, lifecycle, idempotency contract) lives in
ADR-016 and does not change when this decision changes. Keeping the runtime model in its own
ADR means a future change supersedes **only this ADR**, leaving the domain model intact.

## Context

ADR-016 establishes that the database row **is** the pipeline's state and that every
dispatch is idempotent (so at-least-once delivery is correct). What it does not decide is
the runtime: where the orchestrator runs, how many instances, how it finds due work, and how
it bounds concurrent external calls.

The dominant runtime constraint is **external work that runs long relative to a DB transaction**. InfraManager's "run"
API is asynchronous — a short call returns a job id, but the Terraform job it starts runs for
**minutes**, and a condition check re-polls on its interval, bounded by a retry count (ADR-016 §6). The execution model must absorb
that without stalling, and must survive process restarts (ADR-016 guarantees no state is
lost, only that progress pauses).

Two concurrent-writer facts that shape the choice:

- **Cancel is issued by the Admin/API path** — a separate process, in its own short
  transaction — at any time while a worker is mid-tick. Any model that assumes "a single
  process is the only writer" is broken by construction.
- **Process overlap is routine**: rolling deploys, scale events, and restarts create windows
  where more than one instance is live. An in-memory in-flight guard is per-process and
  provides no protection across these boundaries.

Operational facts:

- InfraManager has its **own fixed worker pool** — that is the real ceiling on concurrent
  Terraform jobs. The orchestrator does not need its own hard cap in V1.
- Over-submitting to InfraManager is harmless (idempotent; it only deepens InfraManager's
  queue).
- Scale is small: ~2,000 targets, minute-scale jobs.

## Decision

**Guarantees at a glance.** Each safety property is secured by exactly one mechanism; the rest of
this ADR is how those mechanisms work.

| Guarantee | Secured by | Where |
|---|---|---|
| **One owner per pipeline at a time** | `FOR UPDATE SKIP LOCKED` claim + lease stamp | Decision 2 |
| **No stale clobber** (an expired-lease straggler can't overwrite state) | ownership-guarded write-back `WHERE claimed_by = :claim_token` + a fresh per-claim fencing token | Decision 2, 4 |
| **No terminal resurrection** (a `CANCELLED`/`DONE` pipeline never reverts) | ownership-guarded write-back + exactly one `status` writer per cancel case (Case A: the API path, for an idle row; Case B: the claim holder, which also applies `PENDING → RUNNING` in tx1) — so **no `status` guard is needed** | Decision 2, 4, 6 |
| **At-least-once is correct** (a duplicate dispatch is harmless) | infra-idempotency: TF APIs are duplicate-harmless | ADR-016 §5 |
| **Crash recovery** (a dead worker's pipeline resumes) | lease expiry → reclaim by the next scan; no leader, no journal | Decision 5 |
| **Completion survives a lost result** | code-level `check(target, task, attempt)` over the latest attempt; a lost `TERRAFORM_JOB` result → `executionTimeout` → fresh idempotent re-dispatch (a condition's lost poll heals via lease-expiry reclaim, then re-polls) | Decision 4, ADR-016 §5, §6 |

### 1. Workers pull work from the DB; no single-instance constraint, no leader election

The orchestrator runs as its **own deployable server**. N worker threads — optionally across
multiple pods/replicas — each pull due pipelines from the database. There is no replica count
constraint, no in-memory in-flight guard, no leader election. Horizontal scale and HA are
inherent in the design.

### 2. Pipeline-level claim via `FOR UPDATE SKIP LOCKED` + lease

A worker claims **one** due pipeline by running a short, dedicated transaction (tx1) before
touching any external system:

```sql
BEGIN;
SELECT id FROM pipeline
 WHERE status IN ('RUNNING', 'PENDING')
   AND next_due_at <= now()
   AND (claimed_until IS NULL OR claimed_until < now())
 ORDER BY next_due_at
 LIMIT 1
 FOR UPDATE SKIP LOCKED;
UPDATE pipeline
   SET claimed_by = :claim_token,
       claimed_until = now() + (:lease_seconds * interval '1 second'),
       status = 'RUNNING'          -- PENDING → RUNNING; a no-op when the row is already RUNNING
 WHERE id = :pipeline_id;
COMMIT;
```

*(SQL in this ADR is illustrative pseudo-SQL in PostgreSQL syntax. The implementation targets
**MySQL 8** through JPA/Hibernate: the claim renders as `FOR UPDATE SKIP LOCKED` via a
`PESSIMISTIC_WRITE` lock with a `-2` lock-timeout hint — H2, used in tests, falls back to plain
`FOR UPDATE` — and the schema is generated from entity annotations; there are no hand-written
migrations.)*

**The claim predicate spans both non-terminal states.** A start-delayed pipeline sits in
`PENDING` until its delay elapses (ADR-016 Decision 2); once `next_due_at <= now()` it becomes
claimable exactly like a `RUNNING` one, so the predicate is `status IN ('RUNNING', 'PENDING')`.

**`PENDING → RUNNING` is applied inside the claim transaction (tx1), not the report (tx2).**
The same `UPDATE` that stamps `claimed_by`/`claimed_until` also sets `status = 'RUNNING'` (a
no-op for a row already `RUNNING`), so a claimed pipeline is `RUNNING` the instant a live lease
exists. *Why tx1, not tx2:* it preserves this ADR's invariant that **the claim holder is the only
writer of `status`**, and — because the transition lands atomically with the claim — there is
never a "live claim but still `PENDING`" window. Deferring the transition to tx2 (the first
report) would open exactly that window, and it would break cancel: Case A's guard would reject
the row (a live lease is held) while Case B's `status = 'RUNNING'` guard would also reject it
(still `PENDING`), so the cancel would land nowhere and be silently lost. Doing it in tx1 closes
the window and lets the existing cancel guards stand with the minimal change (Decision 6).

`SKIP LOCKED` ensures two workers racing the same scan claim different pipelines without
blocking each other. The `claimed_by` / `claimed_until` stamp — not a process count — is what
guarantees one pipeline is owned by one worker at a time, **across processes and pods**.

**Fencing token — a per-claim UUID, not a stable pod id.** `claimed_by` is a fresh UUID minted
at the moment of each claim (`:claim_token` in the SQL above), not a reused pod id or thread
name. This matters for stale-straggler rejection: if a worker's lease expires and the same pod
later re-claims the same pipeline, it receives a *different* token, so any in-flight tx2 from the
prior claim holds the old token and no-ops on the `claimed_by = :claim_token` guard. A reused
stable identity would pass that guard and allow the stale write through.

**`ORDER BY next_due_at` + `SKIP LOCKED` yields approximate/fair FIFO, not strict ordering.**
Rows currently held by other workers are skipped, so different concurrent workers may observe
different relative ordering. This is acceptable because pipelines are independent of each other.

**An empty claim result does NOT mean the backlog is empty.** `SKIP LOCKED` silently omits rows
whose leases are held by other workers. The scan loop must not treat a zero-row result as an
idle signal; it should proceed to the next poll iteration normally.

**Execution schema note.** This model adds four execution-coordination columns to the
`pipeline` table — `next_due_at`, `claimed_by`, `claimed_until`, and `cancel_requested`
(boolean) — which are execution metadata owned by this ADR, distinct from ADR-016's domain
state columns.

### 3. Two-transaction split

```
tx1: claim     →     external call (OUTSIDE any transaction)     →     tx2: report
```

External calls (InfraManager, condition checks) run 200 ms–60 s. Holding a row lock across
that window would block every other worker that wants to scan the same row. The two-transaction
split avoids this: tx1 claims the pipeline and commits immediately; the external call runs
unlocked; tx2 commits the result.

### 4. Guarded write-back (ownership-guarded)

**In one line:** a report (tx2) lands only if the worker still owns the claim
(`WHERE claimed_by = :claim_token`); otherwise it no-ops. That single ownership guard — plus the
rule that `status` has one writer per cancel case — is the whole of write-back safety, so **no
`status` guard is needed.**

**The write.** The report transaction (tx2) transitions the task and pipeline with:

```sql
UPDATE pipeline
   SET status = :new_status, ...
 WHERE id = :pipeline_id
   AND claimed_by = :claim_token;
```

**tx2 executes in a fixed order** inside the single transaction: (1) `SELECT ... FOR UPDATE`
the pipeline row; (2) verify `claimed_by = :claim_token` — no-op the whole report if it fails;
(3) read `cancel_requested`; (4) write task and pipeline `status` — if the flag is set, drive the
pipeline and **every non-terminal task** (the current task plus any still-`BLOCKED` successors) to
`CANCELLED` per ADR-016 §7; otherwise apply the normal current-task transition; a terminal
pipeline transition also clears `active_target` in the same write, releasing the per-target
uniqueness slot (ADR-016 §4); (5) write the next `next_due_at` and release the claim.

**Ownership is verified once, at the pipeline level.** The `claimed_by` check is on the locked
pipeline row; the task and pipeline writes then happen under that verified, single-writer claim,
so they need no per-row claim guard — the `task` table carries no `claimed_by` column.

**On success, tx2 releases the claim and advances `next_due_at` atomically.** The same
transaction that advances task/pipeline state also clears `claimed_by`/`claimed_until` and writes
the new `next_due_at` (seeded at creation by the trigger endpoint). Without this release a
pipeline that finishes a step but stays `RUNNING` would sit locked until `claimed_until` passes,
blocking other workers. When the current task reaches `DONE`, the same tx2 flips the next task
`BLOCKED → READY`. Each attempt writes a `task_attempt` row carrying its `response`, and — **when
a poll is actually observed** — a `task_check` summary (a `TERRAFORM_JOB` attempt that reaches its
verdict on the first `check` writes none; `task_check` is 0..1 per attempt, ADR-016 §3), both
under the verified pipeline claim (single writer, no task-level claim needed). The
two kinds differ in shape: a `TERRAFORM_JOB` attempt polls job status many times, so tx2 UPDATEs
the same attempt's `task_check` in place across polls; a `CONDITION_CHECK` poll **is** one attempt
(ADR-016 §6), so each failed poll — not-met or check-error alike — writes a fresh
`task_attempt`/`task_check` pair and increments `fail_count`; while `fail_count < maxFailCount` it
reschedules the next poll (the task's `next_check_at`, projected to the pipeline's `next_due_at` for
the claim scan) at `now() + polling_interval`, and the poll that reaches `maxFailCount` sets the task
(and pipeline) `FAILED` with that poll's `error_code`, scheduling nothing further — no in-place poll loop. A met poll instead writes the final
`task_attempt`/`task_check` pair (the `task_attempt` is the latest row the completion `check`
reads; `task_check` stays write-only) and drives the task
`DONE`, without incrementing `fail_count` or rescheduling.
Task completion is a code-level `check(target, task, attempt)` over the **latest** attempt row — the
reconciler reads only that row, and only for completion; claim, scheduling, and pipeline
transitions never read the attempt tables. For a `TERRAFORM_JOB` a lost attempt result does not
stall the task: it falls through to `executionTimeout` and re-dispatches a fresh `N`-job run
(idempotent); a lost condition poll instead heals via lease-expiry reclaim (Decision 5) and re-polls.

*The above is the mechanism; the rest is why it is race-free.*

**Why no `status` guard is needed.** The lone `claimed_by` guard fences every straggler: a
lease-expired worker that resumes after its pipeline was reclaimed finds `claimed_by` no longer
matches, so its update no-ops (no clobber). A `status = :expected_status` guard adds nothing,
because no *other* writer ever holds a matching live token while the row is terminalized — cancel
writes `status` only on the immediate path (Decision 6), which fires solely when the claim is
null/expired **and clears `claimed_by` as it terminalizes**; for a live-lease pipeline cancel
only sets a flag and the claim holder is the sole `status` writer. Reading `cancel_requested`
under the same row lock tx2's write takes (step 3) is what makes the cooperative cancel race-free
— a cancel committing just before step 3 is observed; one committing just after blocks until tx2
commits, then lands on the next claim.

**Why duplicate external calls are harmless.** The two-transaction split creates three bounded
windows where InfraManager may be called twice:

- **(a) Client timeout** — the caller times out but InfraManager already accepted the job.
- **(b) Crash after dispatch** — the worker dispatches and crashes before tx2 records the
  returned `job_ids`.
- **(c) Lease expiry while stalled** — a worker thread-pool queue wait or GC pause exceeds the
  lease; another worker re-claims and dispatches the same pipeline.

In all three the duplicate **external call** is safe by **infra-idempotency** — TF APIs are
duplicate-harmless (a re-dispatch may create harmless duplicate jobs; the `job_ids` recorded for
the attempt are the latest dispatch's set). A `CONDITION_CHECK` call is a side-effect-free **read**,
so a repeated poll in these windows is inherently harmless (no idempotency contract needed); and
because `fail_count`/`maxFailCount` count only *committed* attempts, a poll lost before its tx2 is
re-run by reclaim without consuming the budget. The guarded DB write solves a *different* problem:
stopping a stale straggler from clobbering DB state. The two are **complementary, not
interchangeable** — idempotency covers the external duplicate, the guard covers the DB write.

### 5. Crash recovery via lease expiry

A crashed worker's claimed pipeline becomes due again once `claimed_until < now()`. No leader,
no human intervention — the next scan reclaims it. Hard constraint:

```
lease_seconds > (max_single_call_timeout × calls_per_step) + pool_queue_wait + scheduling_margin
```

`calls_per_step` matters because a single step is **not** always one external call: a
`TERRAFORM_JOB` `check` loops over up to `N` job-status calls and then up to `N` result-log
fetches within one claim (ADR-016 §3; `TerraformTask`/`TerraformResultRecorder`), so the lease
must cover that whole sequence, not one call. A claimed pipeline can also sit in the worker
thread-pool queue before its external calls even start; that queue wait consumes lease time just
as the calls do. The lease must cover the **full elapsed wall-clock time from claim to tx2
commit** — every call the step makes, plus any queuing delay. The boot-time fail-fast check only
enforces the single-call floor (`lease-duration > api-call-timeout`, Operational reference);
covering the multi-call envelope is operational tuning.

### 6. Cancel: immediate for an idle pipeline, cooperative for a running one

Cancel is issued by the **Admin/API path** (a separate process, its own short transaction). How
it applies splits on one fact — **is a worker running this pipeline right now?**

**In one line:** Case A (no live claim) — the API path writes terminal `status` itself; Case B
(live claim) — the API path only raises `cancel_requested` and the sole claim-holding worker
applies `CANCELLED`. Neither ever shares a live claim token with a worker's tx2, so **no
`status` guard is needed**.

**Case A — the pipeline is NOT being run (no claim, or the lease has expired): cancel immediately.**
This is the common "waiting / not yet picked up" pipeline. A **`PENDING` (start-delayed) pipeline
is always Case A** — it has no live claim by construction (its `PENDING → RUNNING` transition is
performed *by* a claim, Decision 2), so it can never be under a live lease while `PENDING`. The
API path terminates it directly, in its own transaction, with no worker round-trip:

```sql
-- A1: terminate the pipeline, release the per-target uniqueness slot (active_target,
--     ADR-016 §4), AND clear the claim (so an expired-lease straggler's tx2 fails its
--     claimed_by guard); fires only when no live worker owns the row.
--     The guard spans both non-terminal states so a PENDING pipeline is cancellable here.
UPDATE pipeline SET status = 'CANCELLED', active_target = NULL,
       claimed_by = NULL, claimed_until = NULL, last_activity_at = now()
 WHERE id = :pid AND status IN ('RUNNING', 'PENDING')
   AND (claimed_by IS NULL OR claimed_until < now());
-- A2: terminalize every non-terminal task (runs only if A1 updated a row)
UPDATE task SET status = 'CANCELLED'
 WHERE pipeline_id = :pid AND status IN ('BLOCKED', 'READY', 'IN_PROGRESS');
```

**Case B — a worker is running the pipeline (live lease): cancel cooperatively.** The API path
cannot write `status` (the worker is the sole status writer), so it only raises a flag and wakes
the pipeline — this path fires when Case A's `A1` updated 0 rows:

```sql
UPDATE pipeline SET cancel_requested = true, next_due_at = now()
 WHERE id = :pid AND status = 'RUNNING';
```

**Case B's guard stays `status = 'RUNNING'` — it deliberately does *not* add `PENDING`.** Any
pipeline under a live lease has already been transitioned to `RUNNING` by its claim (Decision 2:
the transition is in tx1, atomic with the lease stamp). So a live-lease pipeline is never
`PENDING`, and a `PENDING` pipeline never has a live lease — it is picked up by Case A above.
Widening Case B to `PENDING` would guard against a state that cannot co-occur with a live claim.
This is the payoff of putting the transition in tx1: the two cancel guards partition cleanly —
Case A owns `PENDING`, Case B owns live-lease `RUNNING` — with no overlap window.

The claim-holding worker reads `cancel_requested` at its safe points — right after claiming,
before dispatch, and inside the report transaction (tx2) — and if set, terminalizes **every
non-terminal task** (the current task plus any still-`BLOCKED` successors) and the pipeline to
`CANCELLED` itself, then releases the claim. `next_due_at = now()` makes the pipeline immediately due, so the next scan re-claims it promptly
once the live claim releases. (A condition merely sleeping between polls holds no live claim and is
cancelled immediately by Case A, not here.)

**Why both cases are race-free:**
- **vs a concurrent claim** — both contend on the same pipeline row (the claim via
  `FOR UPDATE SKIP LOCKED`, Case A via its plain `UPDATE`), so they serialize and whichever
  commits first wins: a claim no longer sees a `RUNNING`/`PENDING` row once Case A cancelled it;
  Case A's `A1` no longer sees a free claim once a worker claimed it (so it updates 0 rows and
  falls through to Case B).
- **vs a claim that transitions `PENDING → RUNNING` (Decision 2, tx1)** — tx1's
  `SELECT ... FOR UPDATE SKIP LOCKED` and Case A's plain `UPDATE` both need the pipeline row's
  write lock, so exactly one acquires it first; there is no third interleaving:
  - **tx1's `FOR UPDATE` takes the row lock first** — it stamps the lease and sets `RUNNING`, then
    commits. Case A's `UPDATE` blocks on that lock until tx1 commits, then re-evaluates its guard,
    finds a live lease (`claimed_until` in the future), updates **0 rows**, and falls through to
    Case B — cooperative cancel, applied by the claim holder. (This is the "tx1 first" case.)
  - **Case A's `UPDATE` takes the row lock first** — it sets `CANCELLED`, clears the claim, and
    commits. tx1 never blocks here: `SKIP LOCKED` means that if tx1's scan runs while Case A still
    holds the lock it simply **skips** the row (claims nothing this pass), and if it runs after
    Case A commits the now-free row no longer matches `status IN ('RUNNING','PENDING')` (it is
    `CANCELLED`), so again it claims nothing. (This is the "Case A first" case.)

  Either way the claim predicate's `status IN (...)` and Case A's guard both exclude terminal
  rows, so no path can revive a terminal pipeline — the same argument as the `RUNNING`-only case,
  now covering `PENDING`.
- **vs an expired-lease straggler** — Case A fires only when the claim is null/expired and
  **clears `claimed_by`/`claimed_until`** in the same statement, so a GC-paused straggler that
  resumes finds its token gone and its tx2 `claimed_by` guard no-ops — no resurrection. This is
  why neither case needs a `status` guard.

**Accepted latency edges (Case B):** (i) work that is **claimed but still queued** in the worker
pool observes the flag only when its tx2 runs, so its cancel latency is the queue wait — bounded
by the lease, not just the per-call timeout; (ii) a cancel that arrives **after the worker's
final pre-dispatch flag check** but before the dispatch still lets **one** external call fire —
harmless by idempotency, and recorded as `CANCELLED` at tx2. An already-dispatched job is left to
complete (idempotent infra); an InfraManager-side cancel is a separate follow-up, not required
for correctness.

### Safety mechanisms & tuning knobs (not architectural decisions)

Their exact values live in operational config, not in this ADR:

- **Worker count `N`** caps *concurrent external calls* (`≤ min(N, due pipelines)`). It is
  **not** a requests-per-second guarantee. *In V1 there is no rate-limit-aware backpressure path:*
  a `429`/`503` (any HTTP status) surfaces from the Feign adapter as a `CallFailedException` →
  `CHECK_ERROR`, i.e. it is treated as a failed call on the **normal `fail_count` retry budget**
  (the retry does push `next_due_at` forward by `polling_interval`, but it also consumes budget
  and can fail the task) — a distinct rate-limit deferral that does *not* consume budget is
  **deferred**.
- **Lease duration** (`lease_seconds`) must exceed the **full claim-to-tx2 wall-clock**, not one
  call: a `TERRAFORM_JOB` `check` can issue up to `N` job-status calls **and** up to `N`
  result-log fetches sequentially within a single claim, so the bound is
  `max single-call timeout × (calls per step) + pool queue-wait + scheduling margin` (see
  Decision 5). The code fail-fast only enforces the single-call floor
  (`lease-duration > api-call-timeout`); covering the multi-call step envelope is operational
  tuning. Tune conservatively; a too-short lease has two distinct effects: (1) the **guarded
  write** prevents the stale straggler from clobbering DB state; (2) **idempotency (ADR-016)**
  makes the duplicate *external call* to InfraManager harmless. Neither causes corruption, but
  redundant InfraManager calls consume quota.
- **Claim-predicate index** — the claim predicate (`status IN ('RUNNING','PENDING') AND
  next_due_at <= now() AND (claimed_until IS NULL OR claimed_until < now())`) needs a supporting
  index; without one, every claim degrades to a full sequential scan + sort under concurrent
  multi-worker polling. A btree index on `(status, next_due_at)` covers the hot path — the
  leading `status` column serves the `IN ('RUNNING','PENDING')` filter. (MySQL 8, the target
  engine, has no partial indexes, so a `(next_due_at) WHERE status IN (...)` index is not an
  option.) A second index on `(claimed_until)` supports the admission cap's active-claim count
  (Decision 7).
- **Terraform-job concurrency cap (`slotCap`)** — the soft gate behavior is defined in
  Decision 7; hard-cap enforcement (counter-CAS) remains deferred. InfraManager's fixed pool
  is the real ceiling; over-submission only deepens its idempotent queue.
- **DB polling load control** — workers self-poll, so the claim loop needs explicit load
  controls to avoid hammering the DB during quiet periods: no immediate retry after an empty
  claim result; adaptive **×2 geometric** backoff with jitter — the idle backoff **doubles from
  `backoff-base` (PT0.2S) before each empty-sweep delay is emitted**, so the first emitted delay is
  `2 × base` = 400 ms, then 800 ms → 1.6 s → … capped at `backoff-max` (and never above
  `max-idle-sleep`); it resets to base when work is found (a productive sweep instead reschedules
  at `poll-interval`). That jittered backoff (already capped at `max-idle-sleep`) is **then**
  further capped to the nearest due pipeline time when that is sooner, to avoid over-polling —
  `sleep = min(jittered_backoff, nextDueAt − now())` (the nearest-due cap applies only when
  `nextDueAt` is in the future; a due time already past leaves the backoff delay unchanged). The
  jitter (applied to the backoff before that cap) desynchronizes wakeups across **pods/replicas**
  (the scheduler is one daemon thread per pod, so this desynchronizes replicas, not the worker
  threads within a pod). (The claim-predicate index already limits per-scan cost; this complements it.)

### 7. Admission control and TF slot gate (soft caps)

**`runningPipelineCap`** (config `pipeline.execution.running-pipeline-cap`) is a **soft pickup
target**, not a hard invariant, and it gates **claiming, not creation.** Pipeline creation enforces only per-target uniqueness (ADR-016); its
initial status is set **solely by the start delay**, not by admission: `startDelay > 0` creates
the pipeline `PENDING` — transitioned to `RUNNING` by the first claim once the delay elapses
(Decision 2) — and `startDelay == 0` creates it `RUNNING` immediately, the prior fast path,
unchanged.

**`PENDING` is a start-delay timer, not an admission queue.** These are different axes. `PENDING`
is a wait on a *time* (`next_due_at`); when that time arrives the pipeline is simply
claim-eligible, transitioning to `RUNNING` in the claim itself. It is **not** a slot- or
admission-wait — there is still **no `QUEUED` or `WAITING_SLOT` state**, and a pipeline over the
soft cap is never parked in a distinct status. The cap limits how many pipelines (`RUNNING`, or
`PENDING` whose delay has elapsed) are **concurrently claimed for work**: before claiming beyond
the cap, a worker checks the count of actively-claimed pipelines. Pipelines over the cap simply
stay **unclaimed** and are picked up later via `next_due_at` ordering as slots free. The check is a count-read, so it can **overshoot**: for cap
`M` and `C` concurrent claiming workers, worst-case concurrently-claimed is `M + C − 1`. This
bounded overshoot is **accepted in V1**. A hard cap would require atomic admission (e.g.
`UPDATE pipeline_admission_counter SET used = used + 1 WHERE used < cap`, released on
terminal) — **deferred**.

**TF slot gate.** A TF *slot* is Terraform-job execution occupancy (minutes), a different
resource from API-call concurrency (milliseconds to seconds). `slotCap` (config
`terraform-slot-cap`; the retry delay is `terraform-slot-retry`) is a **soft admission
target** that slows TF dispatch to relieve InfraManager/Terraform pressure. Occupancy is
counted as `IN_PROGRESS` tasks whose `consumes_terraform_slot` flag is set (ADR-016 Schema). Soft gate behavior
in the worker loop: before a TF dispatch, the worker checks slot availability; if available,
it dispatches; if not, it keeps the task `READY`, sets `next_due_at = now() + slotRetry`, and
**releases the claim** (so the worker frees itself to pick up other pipelines). Bounded
overshoot is accepted for the same reason as `runningPipelineCap`; here the racing actors `C`
are the concurrent **workers** checking the slot (bounded by `totalWorkerCount`), not pods. A
hard cap would use a `tf_slot_counter` CAS or an InfraManager-side admission limit — **deferred**.

**Pod count is not a correctness parameter.** The claim mechanism is multi-pod safe by
construction — the DB claim, not a process count, is the coordination primitive — so this model
is **indifferent to how many pods run**: one or many, correctness is unchanged and there is no
`replicas` constraint either way. The only pod-count effect is on the **accepted** soft-cap
overshoot: `totalWorkerCount = activePodCount × workerPerPod`, and overshoot scales with the
number of concurrent admission actors `C` (admission transactions for `runningPipelineCap`,
workers for the slot gate), which more pods can raise. That overshoot is accepted (Decision 7),
so pod count needs no governance here.

**Worker-loop pseudocode (illustrative; slot gate step shown):**

```
loop:
  row = claim_one_due_pipeline()          // tx1: SKIP LOCKED + lease stamp (+ PENDING → RUNNING)
  if row is None:
    sleep(backoff_with_jitter())
    continue

  if row.cancel_requested:
    cancel_pipeline(row)                  // tx2: set CANCELLED, release claim
    continue

  if is_tf_dispatch_step(row) and not slot_available():
    reschedule(row, delay=slotRetry)      // tx2: release claim, push next_due_at
    continue

  result = execute_step(row)              // external call, outside any transaction

  report_result(row, result)              // tx2: guarded write-back, release claim
```

## Considered Options

| Option | Verdict | Why |
|---|---|---|
| A. Multi-worker claim-pull (SKIP LOCKED + lease + guarded writes) | **Chosen** | Multi-process safe by construction; HA + horizontal scale inherent; survives cancel-vs-worker and deploy-overlap races; crash recovery via lease expiry. |
| B. Single server (replicas=1) + in-memory in-flight guard | **Rejected** | Fails the moment there is a second writer: cancel races a worker into terminal resurrection, and any process overlap (rolling deploy / scale / restart) double-transitions status. Idempotency covers double-dispatch but not status-transition races. |
| C. Workflow engine (Temporal / Airflow / broker) | Rejected | A 2–4 step linear chain of minute-scale polls does not justify the operational cost. |
| D. In-memory async chain (no scan) | Rejected | Loses runs on restart/deploy; cannot durably hold delayed retries and due work across restarts. ADR-016 already requires the DB to be the only state. |

## Consequences

### Good

- **Multi-process safe by construction**: the DB claim is the coordination primitive, not a
  process count.
- **HA and horizontal scale** with no leader to operate or debug.
- **Cancel-safe** (two cases, no `status` guard): for a **live-lease** pipeline the claim-holding worker is the sole status writer and applies `CANCELLED` via the `cancel_requested` flag (Case B); for an **idle** pipeline the API path writes terminal status directly, but only after winning the pipeline-row contention with the claim null/expired and clearing it (Case A). Neither shares a live claim token with a worker's tx2, so no resurrection is possible.
- **Crash recovery** is automatic via lease expiry — no recovery journal, no manual step.

### Costs we accept

- **Lease tuning**: `lease_seconds` must exceed max call time; a too-short lease causes
  redundant (safe) work; a too-long lease delays recovery after a crash.
- **Two-transaction split** is more moving parts than a single in-process write: claim tx,
  external call, report tx must all be reasoned about separately.
- **Lease-expiry window**: a crashed worker's pipeline pauses up to one lease period before
  reclaim.
- **Soft admission targets** — both `runningPipelineCap` and `slotCap` are soft gates;
  concurrent admission reads can overshoot by up to `C − 1` (where `C` = number of concurrent
  admission actors: admission transactions for `runningPipelineCap`, workers for the slot gate).
  Hard caps require explicit counter-CAS admission — deferred.

## Operational reference (knobs & metrics)

This section is a reference catalog only. These are **static server config** (e.g. Spring
`application.yml`) — not API-mutable runtime knobs — and their values live in operational
config, not in this ADR. The lease and concurrency values in particular are **tuned in
operation** against observed InfraManager latency, not fixed at design time.

### Knobs

These bind from `pipeline.execution.*` in `application.yml` (the start delay is domain config,
`pipeline.start-delay` — ADR-016 §2):

| Knob (`pipeline.execution.*`) | Meaning |
|---|---|
| `worker-per-pod` | Thread-pool size per pod; caps concurrent external calls per replica |
| `running-pipeline-cap` | Soft pickup target for concurrently claimed pipeline work (candidates are `RUNNING`, plus due `PENDING` about to transition at claim) |
| `terraform-slot-cap` | Soft admission target for concurrent TF-dispatch slots |
| `terraform-slot-retry` | Delay before re-checking slot availability when the slot gate is full |
| `lease-duration` | Claim lease window; see required relationship below |
| `api-call-timeout` | Per-call timeout for InfraManager / condition-check calls |
| `poll-interval` | Sweep interval while work is being found (idle sweeps back off instead) |
| `max-idle-sleep` | Upper bound on idle sleep between claim polls |
| `backoff-base` | Initial backoff interval after an empty-claim result |
| `backoff-max` | Maximum backoff interval (before jitter) |
| `jitter-ratio` | Fraction of the sleep interval to randomize (e.g. ±20 %) |
| `scheduler-initial-delay` | Delay before the first sweep after boot |

Pod topology — `activePodCount` (running replicas) and the autoscaler's `maxReplicas` — is
deployment-owned (Kubernetes), not application config; it enters this ADR only as a factor of
`totalWorkerCount` and the soft-cap overshoot bound.

Required relationship:
`leaseDuration > (maxApiCallTimeout × callsPerStep) + poolQueueWait + safetyMargin`
(same bound as Decision 5 — `callsPerStep` covers the multi-call `TERRAFORM_JOB` step: up to `N`
status calls + up to `N` result fetches per claim). The enforceable **core** of this bound —
`lease-duration > api-call-timeout` (the single-call floor) — is validated fail-fast at boot;
the call-count multiplier, queue wait, and margin remain operational tuning.

### Key metrics

These are a **forward-looking target catalog** — the observability surface to build, **not yet
instrumented** in V1 (no Micrometer/Actuator wiring ships today). The "429/503 count" below is
likewise a target; see the Worker-count knob for how 429/503 are currently handled (as generic
`CHECK_ERROR` retries, undistinguished).

- **active workers** — worker threads currently executing a step
- **total worker count** — `activePodCount × workerPerPod`
- **concurrent API calls** — in-flight external calls to InfraManager / condition checks
- **RUNNING pipeline count** — current `status = 'RUNNING'` row count (excludes `PENDING`)
- **PENDING pipeline count** — current `status = 'PENDING'` row count (start-delayed, not yet
  first-claimed — a row may already be due yet awaiting pickup under cap/worker pressure); tracked
  separately from RUNNING so "not yet started" is distinguishable from "actively executing"
- **pipeline-cap overshoot count** — claims admitted above `runningPipelineCap`
- **empty-claim rate** — fraction of claim polls that returned no row
- **claim QPS** — claim-poll throughput
- **claim latency** — p50/p99 duration of the tx1 claim query
- **due-pipeline lag** — `now() − min(next_due_at)` for unclaimed due rows
- **stale-report discard count** — tx2 no-ops where `claimed_by` guard failed
- **lease-expired reclaim count** — pipelines reclaimed after lease timeout
- **slot-full retry count** — TF dispatch deferred due to slot gate
- **TF-slot overshoot count** — TF dispatches above `slotCap`
- **429/503 count** — back-pressure responses from InfraManager
- **API latency** — p50/p99 of external call durations
- **DB lock wait** — time waiting for row lock during claim scan
- **pipeline completion latency** — wall-clock time from pipeline creation to terminal state

## Links

- [ADR-016](016-install-delete-pipeline-domain-model.md) — the durable domain model this drives

## Glossary

- **Claim** — the act of stamping a per-claim fencing token (a fresh UUID minted at claim time) into `claimed_by`, plus a deadline into `claimed_until`, in a short committed transaction (tx1). Each claim generates a new unique token; the same worker re-claiming the same pipeline after lease expiry receives a different token, ensuring any in-flight tx2 from the prior claim is rejected by the ownership guard. On claim, the worker also checks `cancel_requested` before proceeding.
- **Cooperative cancel (Case B)** — for a pipeline under a live lease, the Admin/API path writes only `cancel_requested = true` (and sets `next_due_at = now()` so the next scan re-claims it promptly once the live claim releases); the claim-holding worker reads the flag at its safe points and applies the terminal `CANCELLED` transition itself, remaining the sole status writer for that live-lease pipeline. (An **idle** pipeline is instead terminated immediately by the API path — Case A in Decision 6.) Neither case needs a per-write `status` guard to prevent terminal resurrection.
- **Due pipeline** — a non-terminal pipeline (`status IN ('RUNNING', 'PENDING')`) whose
  `next_due_at <= now()` and whose lease has expired (or was never set); a terminal row is never due.
- **Guarded write-back** — an `UPDATE ... WHERE id = :id AND claimed_by = :token` that no-ops if the ownership guard fails. Defends against lease-expired straggler clobber; a `status` guard is not required because cancel is cooperative and `status` has a single writer (Decision 6).
- **Lease** — the `claimed_until` timestamp; expiry automatically releases the claim for reclaim by any worker.
- **Two-transaction split** — tx1 (claim) and tx2 (report) are separate committed transactions; the external call runs between them, outside any transaction.

## Revision history

- 2026-06-27: created by splitting ADR-016; execution model extracted here so it can be
  superseded independently of the domain model.
- 2026-06-27: replaced the single-server/in-memory model with multi-worker claim-pull after
  the single-writer premise was found to break under concurrent cancel and multi-session.
- 2026-07-01: aligned with ADR-016's condition rebase (wall-clock `ttl` → retry count) — a
  `CONDITION_CHECK` poll is one attempt, failed polls (not-met or check-error) reschedule at
  `polling_interval`, `executionTimeout` is `TERRAFORM_JOB`-only, and a lost condition poll heals
  via lease-expiry reclaim rather than a fresh schedule.
- 2026-07-03: introduce the `PENDING` start-delay wait state; `PENDING → RUNNING` at claim
  (LIN-30). Claim predicate widens to `status IN ('RUNNING','PENDING')` (the composite
  `(status, next_due_at)` index serves both values as-is); tx1 applies
  the transition atomically with the lease stamp so the claim holder stays the sole `status`
  writer; Case A cancel guard widens to include `PENDING` while Case B stays `RUNNING`-only
  (a live-lease pipeline is already `RUNNING`); Decision 7 restated (creation status set by start
  delay, `PENDING` is a start-delay timer not an admission queue); PENDING pipeline metric added.
- 2026-07-03 (alignment pass, post-LIN-30 audit): restored the composite `(status, next_due_at)`
  claim-index description — the LIN-30 wording port had imported a PostgreSQL partial-index
  phrasing from its source repo, but MySQL 8 (the target engine) has no partial indexes and the
  implementation has always used the composite index; added the MySQL 8 + JPA pseudo-SQL note
  (Decision 2); documented `active_target` uniqueness-slot release in Case A cancel and in tx2's
  terminal write (ADR-016 §4's mechanism); named the slot-gate occupancy source
  (`consumes_terraform_slot`, Decision 7); aligned the knob catalog with the
  `pipeline.execution.*` config keys and moved pod topology out of application config.
- 2026-07-04 (alignment pass 2, codex round 1): corrected the tx2 write-back to note `task_check`
  is 0..1 per attempt (a `TERRAFORM_JOB` attempt that completes on its first `check` writes none);
  stated that V1 has **no distinct 429/503 backpressure** (they surface as `CHECK_ERROR` on the
  normal retry budget); widened the lease bound to the **multi-call Terraform step envelope**
  (N status + N result calls per claim), noting the fail-fast check only enforces the single-call
  floor; corrected the backoff example to the actual **×2 geometric** doubling and the jitter
  scope to **pods/replicas**; labeled the Key-metrics catalog **not yet instrumented**.
