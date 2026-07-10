# Review Ledger — recurring findings & owner preferences

The harness memory. Every review finding (codex / opus / human) and every preference the owner states
is recorded here. A row seen **≥ 2 times** is **promoted** to an automated detection so the same note is
never made a third time by a human:

- **rule-based** → a grep in [`scripts/recurring-check.sh`](../scripts/recurring-check.sh) (runs on
  staged Java via the pre-commit hook; also `bash scripts/recurring-check.sh --staged`). For
  syntactically-detectable patterns.
- **agent-based** → a check in the [`recurring-review`](skills/../agents/recurring-review.md) agent.
  For semantic patterns a grep cannot judge.

Process: [`.claude/skills/review-harness/SKILL.md`](skills/review-harness/SKILL.md). An intentional
exception to a rule is annotated inline with `// harness-allow: <rule> — <reason>`.

## Promoted (≥ 2 occurrences — detection active)

| Pattern | Occurrences | Detection | Signal |
|---|---|---|---|
| **guarded-CAS** — a terminal/gated status transition must use a guarded update (an `@Modifying` CAS filtering on the expected prior state, 0-row handled, or `@Version`), never a blind `findById→setStatus→save` | (1) spring-java21 §3/§7 · (2) R1 `PipelineControl.cancel` blind write (codex + opus P1) | agent | a blind terminal write |
| **targeted-catch** — a boundary `catch` must verify it is the intended cause and rethrow the rest | (1) spring-java21 §5.7 · (2) R1 `PipelineCreator` broad `DataIntegrityViolationException` (codex + opus) · (3) terraform-client `TerraformResultRecorder` broad `DataIntegrityViolationException` (recurring-review agent; fixed with the `PipelineCreator` idiom + `TerraformResult.ATTEMPT_JOB_CONSTRAINT`) | agent (+ rule flags `Exception`/`Throwable`) | a catch recovering from a whole class without discriminating |
| **no-hidden-test-tx** — non-repository tests must not run in a wrapping test transaction | (1) spring-java21 §4 · (2) R1 `@DataJpaTest` state-machine tests (opus P1) | rule | `@Transactional` on a `*Test` without `NOT_SUPPORTED` |
| **exhaustive-switch** — closed-enum switches enumerate all cases, no `default` swallow | (1) spring-java21 §1/§6 · (2) R1/R2 `advance()` default arm | rule | `default ->` / `default:` |
| **trust-boundary-null** — a value crossing a trust boundary (a name/row from the repository, a return from an external API/client) is null-guarded: resolve to a clean failure (`UNKNOWN_TASK`) or translate (`CHECK_ERROR`), never propagate an NPE; registries reject null/blank keys at boot | (1) owner · (2) R5 `TaskTypeRegistry` null/dup + `TerraformTask` null jobId/poll (codex + opus) | agent | a boundary read used without a null check |
| **index-coverage** — every repository query (derived or `@Query`) filters on columns backed by a `@UniqueConstraint`/`@Index`; prefer querying the indexed column (e.g. `activeTarget`) over an unindexed one (`target`,`status`) | (1) codex R5 · (2) sonnet R5 (unindexed `findFirstByTargetAndStatus`) | agent | a finder on a non-indexed column |
| **list-get-first** — a `List` first/last read uses Java 21 `getFirst()`/`getLast()`, not `.get(0)`/`.get(size-1)` | (1) spring-java21 §1 · (2) ADR-021 retro #9 | rule | `.get(0)` |
| **extensibility-not-by-name** — an engine/gate branches on a `TaskType` property, never on a type's `NAME` constant (adding a type must not require an engine edit) | (1) AGENTS.md #5 / TaskType strategy · (2) ADR-021 retro #4 | rule (`service/.NAME.equals(`) + agent | `Type.NAME.equals(` in an engine/gate |
| **optional-idiom** — an `Optional` is consumed as one (`map`/`filter`/`orElseThrow`), never degraded via `.orElse(null)` then re-checked `== null` | (1) spring-java21 §1 · (2) ADR-021 retro #2 | agent | `orElse(null)` feeding an `== null` |
| **intention-revealing-guards** — a non-trivial inline compound boolean is extracted to a named predicate/method | (1) spring-java21 §5.5 · (2) ADR-021 retro #6 | agent | a multi-clause `&&`/`||` puzzle in a condition |
| **explicit-domain-naming** — steps/methods named by role (`claim`/`run`/`writeBack` — matches the real `StepReporter.writeBack`), not positional jargon in identifiers; domain nouns not bare generics (`terraformSlot` not `slot`) | (1) owner naming rule · (2) ADR-021 retro #5/#7/#8 | agent | jargon or a bare generic noun in an identifier |
| **repo-owns-single-result** — "the one matching row" is an intention-named repository method returning `Optional`, not a service `PageRequest.of(0,1)` + `stream().findFirst()` | (1) spring-java21 §3 · (2) ADR-021 retro #10 | agent | a service that pages-then-picks-first |
| **error-code-enum** — an API error code is built from the `OrchestrationErrorCode` enum (`PREFIX + name()`), never a magic `"ORCHESTRATION_..."` literal re-spelled outside the enum | (1) constant-single-source (changelog) · (2) ADR-021 retro #14 | rule (`"ORCHESTRATION_` literal, enum + tests excluded) + agent | a bare error-code literal outside the enum |
| **input-contract-guard** — a public entry point's missing/invalid required arg fails as a dedicated `OrchestrationException` subtype (`PipelineNotFoundException` — stable status + `code`), never a bare NPE or generic `IllegalArgumentException` | (1) trust-boundary-null · (2) ADR-021 retro #12 | agent | a public entry dereferencing a required arg unguarded |
| **controlled-boundary-exception** — a raw infra exception (`DataIntegrityViolationException`…) is wrapped into a controlled `OrchestrationException(status+code)` at the service boundary; `GlobalAdvice` handles it in one place + a catch-all that logs the cause | (1) single-translation-boundary (changelog) · (2) ADR-021 retro #13 | agent | a raw infra exception reaching `GlobalAdvice` |
| **no-inline-fqn** — a class is imported and used by its short name, never written fully-qualified inline in code (declaration / `new` / call / cast / type arg); JPQL enum literals in `@Query` strings and javadoc `{@link}`/`{@code}` are exempt | (1) owner (stated hard: "반드시 피해야되는 패턴") · (2) PR#15 review — codex found `PipelineScheduler` `java.time.Clock` + inline FQNs in 3 tests | rule | a `java.`/`javax.`/`com.bff.` FQN inline in code |
| **no-html-javadoc** — javadoc is plain text; no HTML markup tags (`<b>`/`<p>`/`<em>`/`<i>`/`<strong>`). Diff-scoped: don't ADD tags (`<pre>` layout blocks allowed) | (1) owner (memory: dislikes `<b>`/`<p>`/`<em>`) · (2) PR#15 review — codex flagged added `<b>`/`<p>` | agent (diff-aware; rule too noisy — codebase is saturated) | an HTML tag added to javadoc |
| **dto-builder** — a wide DTO (adjacent same-type or boolean components) is built with `@Builder`, never a positional `new` where a swapped argument still compiles | (1) ADR-021 retro #3 · (2) R6 post-PR#18 review — `PipelineQueryService` built `PipelineDetail`(19 args)/`TaskDetail`(18 args) positionally | agent | a positional `new` of a wide DTO |
| **enum-column-widening-safe** — a persisted, extensible enum column is mapped VARCHAR (a `@Convert` `AttributeConverter` like `PipelineStatusConverter`, or `@JdbcTypeCode(SqlTypes.VARCHAR)`), never a bare `@Enumerated(EnumType.STRING)` that Hibernate renders as a native MySQL `enum(...)` — `ddl-auto=update` never ALTERs an existing enum column, so adding a value breaks insert on a live DB (`create-drop` tests never catch it) | (1) `Task.operation`/`TaskOperationConverter` write-safety · (2) LIN-28 (#24) converted every persisted enum; the LIN-30 codex review re-caught it on a stale base | rule (`@Enumerated(EnumType.STRING)`; tree is 100% converters → regression-only) + agent (converter read-strictness: `valueOf` for a state enum, `find→null` for a display-only value) | a native-enum-mapped persisted column |

| **column-length-guard** — a String crossing a trust boundary (request DTO field, external API value) into a `@Column(length = N)` write is length-guarded before flush: typed 400 at the boundary, guard and `@Column` sharing one entity constant — never a raw `DataIntegrityViolationException` surfacing as a generic 500 | (1) R8 `TerraformResultRecorder` — external resultPath/jobId exceeding column length traps write-back · (2) R10 `NotificationChannelService.upsert` — webhook 512/label 128 unguarded (recurring-review FLAG; fixed with entity constants + typed 400) | agent (pattern 16) | an unguarded boundary String persisted into a bounded column |

## Watch-list (1 occurrence — recorded, promote on the next hit)

| Preference / finding | Source | Promote-to |
|---|---|---|
| MySQL is the target DB; schema is JPA-generated (`@Table`/`@Index`/`@UniqueConstraint`), no Flyway / raw SQL migrations | owner | rule (flag added Flyway dep or `*.sql` under resources) |
| Keep the absolute file count down; prefer a static util / nested type to a new file | owner; spring-java21 §10 | agent |
| An `interface` must earn its place: a real external boundary (e.g. `InfraManagerClient`, prod + test fake) OR genuine N-impl polymorphism (e.g. `TaskType`: Terraform/Condition). A single-impl interface a concrete class or static util would serve is flagged | owner; spring-java21 §10 | agent (interface-justification) |
| Business failures are values (`ErrorCode`); only external/infra failures are exceptions | owner; spring-java21 §5.7; exception-strategy.md | agent |
| Extensibility seams are documented, not pre-built (YAGNI) | owner; extensibility.md | agent |
| Configuration lives in env vars (repo precedent: infra-manager) — an admin-managed settings surface (DB table + REST + UI) is built only on explicit owner request, even when a synced spec prescribes it; gate that scope with the owner before implementing | owner (2026-07-09, ADR-022 notify: spec §6 admin channel management replaced by `PIPELINE_NOTIFY_SLACK_WEBHOOK_URL`) | process + agent |
| Derive "done" from the ADR (`docs/acceptance-criteria.md`); don't ask for sign-off | owner | process |
| Respond to the owner in Korean | owner | process |
| Javadoc explains FUNCTION in plain Korean — an ADR/section reference or compressed jargon (술어/파생/회계/lease/fencing untranslated) must never substitute for the explanation; spell terms out (lease→점유 시간, fencing token→점유 확인용 토큰), one trailing "자세한 배경은 ADR-N 참조" line at most; do NOT mirror the dense style of older files (exemplar: `NotifySettings` header, 2026-07-09 rewrite) | owner (2026-07-09: "알아먹기 힘들다… ADR의 뭐를 참조했다 이런것보다 그냥 기능을 설명해") | agent |
| Prefer `Stream`/`IntStream.range` (enumerate) over a `for` loop where it reads cleanly | owner | agent |
| Purposeful names; **no abbreviations** in ANY identifier (class, method, field, variable) — reveal the role: `ImClient`→`InfraManagerClient`, `im`→`infraManager`, `seq`→`sequence`, `ttl`→`timeToLive`, `cve`→`constraintViolation`, catch `e`→`exception`. Allowed: `id`, `main(args)` (owner stated 3×). Role-based collection names (`tasks`, `pipelines`, `settings`) are correct — reveal-the-role, not echo-the-type (ADR-021 retro #1 folded in here; owner keeps role names) | owner | agent (recurring-review pattern 6) |
| Layered package convention: `entity / service / client / controller / dto / repository / utils` (entity also holds domain enums; dto holds transport values; app wiring at the root package) — no abbreviated package names like `im` | owner | rule (flag a new top-level pkg outside the set) |
| REST-layer exception handling is `GlobalAdvice` (`@RestControllerAdvice`); domain failures stay values. **RESOLVED (R6):** `GlobalAdvice` moved `advice/` → `controller/` to match AGENTS.md #6 ("controller — REST advice"; `advice` was never in the allowed package set). If the owner prefers a separate `advice/` package, revert the move AND amend AGENTS.md #6 together | owner; AGENTS.md #6 | resolved (code now matches the doc) |
| A global catch-all exception handler must **log the cause** (never return a generic body and drop the trace) | sonnet R5 | agent |
| Duration-typed field suffix convention: omit for `Interval`/`Timeout`/`Sleep`/`Retry`; spell out when ambiguous (`backoffBase`/`backoffMax`) — **PENDING owner decision** before promotion | ADR-021 retro #15 | (undecided) |
| VS Code Lombok `@NonNull` false warnings → `.vscode/settings.json` `"java.compile.nullAnalysis.mode":"disabled"` (editor-only, code-unrelated) | ADR-021 retro #16 | none (editor config) |

## Changelog

- **terraform_job_state review (feat/terraform-job-state-observation) → codex 2 rounds, merge-ready.**
  Added a fourth write-only observation table `terraform_job_state` (per-job in-progress state, upserted every
  poll in the run phase, tx-free/best-effort like `terraform_result`) + `TaskAttemptView.job_states[]` inline +
  a per-job state endpoint + ADR-016 §3 editorial. Codex (gpt-5.5 xhigh) **R1**: no P0/P1; one **P2** — the
  `NOT_SUPPORTED` (non-rollback) integration tests now write `terraform_job_state` rows during polling but omitted
  it from their `@AfterEach` cleanup (clean-state policy). Fixed: added `terraformJobStateRepository.deleteAll()`
  to the 6 affected suites. **R2**: no P0/P1/P2, merge-ready. Confirmed observation-only (engine reads stay limited
  to the latest `task_attempt`; the table is touched only by the recorder + admin query) and `pollAndObserve`
  preserves prior `CallFailed`/`CallTimeout` propagation. **Watch-list candidate (2nd occurrence — 1st was
  `terraform_result`):** a new write-only observation table written by non-rollback integration tests must be added
  to their `@AfterEach` deleteAll; promote to an agent check on the next hit.
  **오너 결정(리뷰 중):** `resultPath`는 "어디에도 쓸 수 없는 정보" → 전면 제거. `TerraformJobStatusResponse`·
  `TerraformPoll`에서 안 읽고, `terraform_result`의 `result_path` 컬럼·`TerraformResultSummary`/`Detail`·메타 투영·
  recorder·ADR-016 §3 스키마까지 걷어냄. `failReason`은 유지(`last_fail_reason`가 씀). terraform 완료 로그는
  오직 `/result`(String→`content`)에서만 얻는다.

- **enum-column-widening-safe promoted (rule + agent).** The LIN-30 (PENDING status) codex doc-review flagged
  `Pipeline.status` as a native MySQL `enum(...)` that `ddl-auto=update` won't widen — a real P0, but caught on a
  **stale base**. `origin/main` had already fixed it in **LIN-28 (#24)**, mapping every persisted enum through an
  `AttributeConverter` (`PipelineStatusConverter`/`PipelineTypeConverter`/`CloudProviderConverter`/`ErrorCodeConverter`/
  `TaskStatusConverter`, varchar, strict `valueOf` read). 2nd occurrence of the pattern (1st: `Task.operation` /
  `TaskOperationConverter`) → promoted. **Rule:** `recurring-check.sh` greps `@Enumerated(EnumType.STRING)`; the tree is
  100% converters post-LIN-28, so it stays silent until a regression reintroduces a native-enum column. **Agent:** the
  recurring-review agent judges converter read-strictness a grep can't — `valueOf` (fail-fast) for a state enum the
  engine branches on (`status`), `find→null` (degrade) for a display-only value (`operation`). Lesson recorded: **fetch
  and diff `origin/main` before assuming the base** — the LIN-30 branch was first cut from a stale local ref and had to
  be re-based (the redundant `@JdbcTypeCode` fix was then dropped).

- **R6 — post-PR#18 whole-repo review (docs/post-pr18-code-review.md).** The engine/domain had converged
  after prior rounds; nearly every finding sat in the freshly-merged REST layer (PR #18). Harness moves:
  - **dto-builder** promoted to **agent** pattern 15 (2nd occurrence: ADR-021 retro #3 + the positional
    19-arg `new PipelineDetail(...)` / 18-arg `new TaskDetail(...)` in `PipelineQueryService`). Fixed by
    putting `@Builder` on both records.
  - **advice-package inconsistency resolved**: `GlobalAdvice` moved to `controller/` (AGENTS.md #6 is the
    doc of record; `advice` was outside the allowed package set). Watch-list row updated.
  - Promoted-rule regressions found & fixed in PR #18 code: **list-get-first**
    (`chain.get(chain.size()-1)` in `toDetail` — note: the grep only catches `.get(0)`, the `.get(size-1)`
    half stays a human/agent catch; + 3 test `.get(0)` the grep DID catch), **optional-idiom**
    (`currentTask(...).orElse(null)` re-checked `== null` in `PipelineQueryService`).
  - **index-coverage PASS**: PR #18 had already added `idx_pipeline_status_created` /
    `idx_pipeline_target_created` for its admin queries — recorded as a pass, no change.
  - Clean-code fixes: `long[]{done,total}` magic-index aggregation → nested record `TaskProgressCount`
    (also removed a `static final long[]` mutable-array constant); the last scattered `@Value`
    (`scheduler-initial-delay`) folded into `ExecutionSettings` (validated like its 11 siblings);
    terminal pipelines now report `dueLagMillis = 0` (was: grew forever after termination).
  - Test-quality: `runUntilTerminal` helpers now fail loudly on non-termination; the
    "WithoutHammering" scheduler test asserts the claim-attempt count (was: name promised what no assert
    checked — discriminating-test-assert again); `WithoutNPlusOne` dropped from a test name that never
    measured statements; 6 unused imports deleted; `PipelineQueryServiceTest` renamed to the suite's
    camelCase convention.
- **PR #15 review (CONDITION_CHECK ttl→retry-count) → harness.** Two owner points promoted:
  - **no-inline-fqn** → **rule** (`recurring-check.sh`). Owner stated it as a hard rule; codex found 4 inline
    FQNs (`PipelineScheduler` `java.time.Clock` field/ctor + `java.util.Arrays`/`Supplier`/`ConditionPoll`
    in tests). The grep is silent on the (now clean) tree and excludes `import`/`package`, javadoc
    `{@link}`/`{@code}`, and JPQL enum literals in `@Query` strings (string-continuation lines starting
    `"`/`+ "`). Verified 0-match on `origin/main` and a positive hit on a field/`new` sample.
  - **no-html-javadoc** → **agent** pattern 14 (diff-aware). Owner dislikes `<b>`/`<p>`/`<em>` in javadoc;
    codex flagged tags this change had added. NOT a grep — the existing codebase is saturated with `<p>`,
    so a staged-file grep would be all noise; the agent flags only tags on **added/changed** lines
    (`<pre>` layout blocks stay allowed).
  - Reinforced **trust-boundary-null** (3rd occurrence): `ConditionCheckTask` now translates a null
    `checkCondition()` result to `CallFailedException` instead of leaking an NPE — same pattern as
    `TerraformTask`'s null jobId/poll guard. No ledger change (already promoted); noted here.
  - Review lesson (not a rule): when a change is scoped to ONE kind (condition), prove the OTHER kind
    (terraform) is untouched — the codex diff review confirmed additive-only shared switches (new
    `ConditionMet`/`ConditionNotMet` arms) with the terraform `Pending`/`Succeeded`/`Failed`/`complete`/
    `retryOrFail` paths byte-for-byte unchanged.
- **ADR-021 refactor-session retro → harness** (rebased onto `origin/main` = PR #9, which **landed the
  ADR-021 claim-pull execution engine** — `PipelineScheduler`/`PipelineClaimer`/`PipelineWorker`/
  `StepRunner`/`StepReporter`, `OrchestrationException`/`OrchestrationErrorCode`, `advice/GlobalAdvice`).
  Recorded 16 findings; wired **9** into detections (list-get-first, extensibility-not-by-name,
  error-code-enum as **rules**; optional-idiom, intention-revealing-guards, explicit-domain-naming,
  repo-owns-single-result, input-contract-guard, controlled-boundary-exception as **agent** patterns 7–13).
  Root cause was NOT a missing rule — several findings were already known but never applied → the real gap
  is a **pre-commit gate**. Gate is now: run `bash scripts/recurring-check.sh --staged` **and** spawn the
  `recurring-review` agent (and act on both) before committing a diff touching status transitions /
  exception boundaries / new interfaces or beans. Notes where reality differed from the retro's phrasing:
  - #1 field naming: owner keeps **role names** (`tasks`/`pipelines`/`settings`); NOT type-camelCase.
    Folded into the no-abbreviations agent pattern — no grep (would have flagged all ~15 injected fields).
  - #8 `report`→`writeBack`: **applied** in the real code (`StepReporter.writeBack`); the class kept the
    `Reporter` noun, the method took the role verb. The agent pattern matches this.
  - #11 advice: the code has `advice/GlobalAdvice` (PR #9) but AGENTS.md #6 text still says `controller/`.
    **Unresolved doc↔code inconsistency** — no advice-placement rule promoted until the owner settles the
    package of record (a rule either way would fight one of the two). Recorded in the watch-list.
  - #12–14 `OrchestrationException`/`OrchestrationErrorCode` are **implemented**, not forward-looking. The
    error-code-enum rule flags a `"ORCHESTRATION_` literal re-spelled *outside* the enum (the enum's own
    `PREFIX` and the tests that assert the resolved wire string are excluded → 0-match).
  - Dropped the `LoggerFactory`→`@Slf4j` rule (repo standard is manual `LoggerFactory`; zero `@Slf4j`) and
    the `.orElse(null)` grep (2 live uses — agent optional-idiom covers it without nagging).
  - #15 (Duration suffix) left undecided pending owner; #16 (VS Code) is editor-only, not wired.
- **Clean-code & exception campaign (4 rounds, 21 reviews — codex×4 + opus×17).** New recurring rules:
  - **closed-exception-vocabulary / single-translation-boundary** — the external boundary catches only a
    closed exception family (`CallTimeout`/`CallFailed`) in ONE helper (`runExternalCall` over
    execute/check/postCheck); never a broad `catch (RuntimeException)` (a bug must fail-fast, not become a
    business `CHECK_ERROR`); an interrupt propagates naturally. Promote on the next hit.
  - **constant-single-source** — an identifier/constraint literal used in two places (a JPA
    `@UniqueConstraint(name=...)` AND the code that recognizes that violation) must be one
    `public static final` (e.g. `Pipeline.ACTIVE_TARGET_CONSTRAINT`); a silent rename break is a P1.
  - **discriminating-test-assert** — a test named for a specific outcome (`...IsCheckError`) must assert
    the discriminating value (the `ErrorCode` on the attempt row), not just a shape shared with another
    mapping (READY + failCount). Otherwise a mis-mapping regression passes.
  - Owner structural rules recorded: `service` = `@Component`/`@Service` beans ONLY; non-bean domain
    types in `model` (`TaskType`/`TaskProgress`/`Recipe`/`RecipeStep`), enums in `enums`, transport in `dto`.
  - Owner: **class-header Javadoc in Korean** (detailed; identifiers stay English; no inline comments).
  - Owner: config defaults `executionTimeout 50m`, `maxFailCount 2`; lifecycle `execute → check → postCheck`.
  - Boolean-flag (§5.11) flagged on `runExternalCall(..., recordObservation)` twice but KEPT (private helper,
    self-documenting param) — a deliberate, recorded exception.
- Seeded from this session's codex (3 rounds) + opus review and the owner's stated preferences.
- R5 (TaskType redesign + repackage): promoted **trust-boundary-null** and **index-coverage** to the
  agent (each seen twice); recorded the catch-all-logging preference; updated interface-justification to
  bless genuine N-impl polymorphism (`TaskType`). The cancel/advance lock-order P1 is dispositioned in
  `docs/acceptance-criteria.md` (Deferred) as an ADR-021 concern.
- R8 (review-fix: operation converter + recorder boundary catch): the exception-catch-targeting rule gains a
  recorded, deliberate exception — `TerraformResultRecorder.recordFinishedJobs` wraps each job in
  `catch (RuntimeException)` (after rethrowing `CallInterruptedException`). Justification: the component is
  observation-only by contract ("어떤 기록 실패도 태스크 판정을 바꾸지 않는다"); a propagating save failure
  (e.g. external resultPath/jobId exceeding column length) blocks write-back and traps the pipeline in a
  lease-expiry crash loop. The failure does NOT become a business ErrorCode — it degrades to an error log
  (duplicates stay debug via the inner targeted catch). Do not flag this instance; DO flag any new broad
  catch that converts a bug into a business outcome.
- R9 (failure_detail + terraform result read path, feat/terraform-result-exposure): codex round 1 —
  no P0/P1, MERGE-READY; recurring-review agent — all charter dimensions PASS (transition semantics
  additive-only, catches still targeted, `TerraformResultMetadata` blessed as a genuine Spring Data
  projection boundary). **dto-builder recurred post-promotion (3rd occurrence)** on the three new/widened
  wire DTOs (`TerraformResultSummary`/`TerraformResultDetail`/`TaskAttemptView`, adjacent boolean/String
  positional args) — fixed same-session with `@Builder` + named `from(...)` construction. Lesson
  reinforced: the pre-commit gate (recurring-check + recurring-review) ran only AFTER the commit; run it
  before. Also fixed one added `<p>` javadoc tag (owner: no HTML in comments). New read-path precedent
  recorded: admin queries MAY read `terraform_result` (design §4.5) — the write-only invariant is about
  the engine (claim/scheduling/transition), and the body column stays out of list/detail queries via the
  metadata projection (only the P11 single-row endpoint pays the MEDIUMTEXT I/O).
- R10 (ADR-022 terminal-state notification, feat/adr-022-terminal-notification): three-reviewer cycle to
  merge-ready — codex xhigh R1 82 → R2 88 → R3 **95 / merge-ready, findings 0**; opus R1 93 / R2 both
  merge-ready; recurring-review PASS after fixes. Fixed along the way: **rollout replay unguarded** (ADR-022
  §5 legacy cutoff now enforced IN CODE at first channel creation — hand-SQL is banned by AGENTS.md #3, and
  a channel row is a precondition for any delivery, so first configuration == introduction moment);
  **failed_task carried the mechanism** (`taskName` = TERRAFORM_JOB/CONDITION_CHECK) instead of the recipe
  identity (`taskDefinition`, null-degraded rows fall back to mechanism); **give-up alert had no pollable
  surface** (added `GET /admin/notification-channel/health` exposing `countGivenUp` + both ages — the
  DB-derived canonical alert source); **delivery WARN lacked `attempt`** (`onFailure` returns the
  post-increment count as `OptionalInt`, empty = fenced stale no-op); **column-length-guard promoted**
  (2nd occurrence after R8 — see Promoted); **3xx-as-ack** (RestClient default only throws on 4xx/5xx; a
  3xx would stamp `notified_at` and silently drop the notification — explicit `onStatus` for non-2xx
  non-error, keeping the default 4xx/5xx path for `resp_class` fidelity); **concurrent first-upsert raw
  500 → typed 409** (`saveAndFlush` + `ConstraintViolationException` discrimination, `PipelineCreator`
  idiom). Process lesson: the codex/opus/harness triple found disjoint real issues (rollout & vocabulary
  & 3xx from codex, admin UX & test gaps from opus, boundary guards from the harness) — keep running all
  three on concurrency-heavy features.
- R11 (ADR-022 notify simplification, owner decision 2026-07-09): after R10 reached merge-ready, the owner
  cut the admin surface — **webhook comes from an env var, not an admin-managed table**. Deleted the entire
  §6 admin group (~10 main files: `notification_channel` entity/repo/service/controller, 4 DTOs, 2
  exceptions, SSRF/masking/test-send/health); the durable core (state-derived claim/lease, backoff/give-up,
  PII payload) stays. Rollout cutoff moved from first-channel-creation backfill to the ADR §5 **alternative
  predicate** `last_activity_at >= pipeline.notify.enabled-after` (required when enabled — fail-fast).
  The R10 column-length-guard occurrence site (`NotificationChannelService`) was deleted with the surface,
  but the promoted pattern stands (R8 remains live). Lesson: a synced spec's surface area (admin UI,
  management REST) is not implicit owner intent — gate that scope BEFORE building; env-var config is the
  repo default (infra-manager precedent). Deviations recorded in the orchestrator copies of the ADR/spec.
- R12 (ADR-022 single-transaction redesign, owner decision 2026-07-10): second owner simplification pass
  ("still too much code for a simple notification"). Delivery concurrency rewritten from
  claim-lease/fencing/two-tx to a **single transaction holding the row lock across the Slack call**
  (lock → build payload → HTTP → record → commit). The lease/fencing machinery exists for ADR-021's
  premise (calls too long to hold a lock); notify's call is bounded (CALL_TIMEOUT 10s), one thread per
  pod, and nothing else locks terminal rows — so the row lock itself replaces lease + fencing, and the
  stale-worker state class disappears structurally. Deleted: `NotifyClaimer`/`NotifyWriteBack`/
  `NotifyScheduler`/`NotifyClaim`/`NotifyRepository` (→ `TerminalNotifier` + 2 queries folded into
  `PipelineRepository`), lease columns (5→3), exponential backoff+jitter (→ linear attempts × 1min),
  far-future give-up sentinel, idle geometric backoff (→ fixed 10s sweep with drain loop), 9 of 12
  config keys (→ code constants; env keys = enabled/webhook/enabled-after). Owner-set values:
  MAX_ATTEMPTS=3, give-up backlog re-alert poller KEPT (5min ERROR). Revert triggers documented in
  ADR-022 §2 (multi-sink, long timeouts, batch updates on terminal rows → reintroduce two-tx split).
  Harness note for reviewers: the delivery-failure catch INSIDE the transaction lambda is intentional
  (failure record must commit, not roll back) — catch scope is still the delivery call only.
  Post-redesign review (codex xhigh 94 merge-ready / opus 75 request-changes / recurring-review PASS):
  opus found a real P0 that predated the redesign — the failure WARN passed the raw throwable to SLF4J,
  and Spring's `ResourceAccessException`/`RestClientResponseException` messages embed the full request
  URL, i.e. the webhook secret, so every Slack timeout logged the secret. Fix: `SlackNotifier.deliver`
  is now a **secret-redaction boundary** — it catches everything from `post()` and rethrows a fresh
  `RestClientException` carrying only a response classification (`http NNN` or exception class names),
  no message copy, no cause chain; `TerminalNotifier` narrows its catch to `RestClientException` (other
  exceptions escalate as bugs instead of burning attempts) and logs only the sanitized message.
  Regression tests: `aDeliveryFailureNeverExposesTheWebhookUrl` (real ResourceAccessException vector via
  a broken ClientHttpRequestFactory) + `aNonDeliveryExceptionEscalatesWithoutBurningAnAttempt`.
  Pattern lesson (watch-list candidate): **any log that passes a raw exception from an HTTP client call
  made to a secret-bearing URL is a leak vector** — redact at the client boundary, not at each log site.
- R13 (notify payload extension, owner request 2026-07-10): payload 7 → 10 fields (`cloud_provider`,
  `environment`, `detail_url`; schema_version 1 → 2), Slack headline gains `[env]` tag + detail link,
  `target_ref` display label renamed to `target_source` (payload field name unchanged). ADR §4 link rule
  amended: blanket "no url field" → exactly one allowed link (`detail_url` = configured console base +
  pipeline_id, assembled only in `TerminalNotifier.toDetailUrl`). Settings env keys 3 → 5 (`environment`
  default local, `detail-url-base` default localhost console — stg/prd must override via env).
  PII test reworked: 10-field allowlist, raw-identifier scan excludes `detail_url` (a console URL
  legitimately contains "://"/"host"), URL-assembly shape pinned in TerminalNotifierTest instead.
- R10 (last raw response capture, feat/terraform-job-state-observation): full-body capture of the
  terraform status response (`terraform_job_state.last_response`, TEXT) and the condition-check response
  (`task_attempt.response`), via a delegating `@JsonCreator(mode=DELEGATING)` that binds the whole body to
  `JsonNode`, extracts the typed fields, and keeps `node.toString()` as `raw` — so fields the typed DTO
  drops survive. Codex 4 rounds → merge-ready. Findings fixed:
  - **R1 P1 lenient-boolean-coercion (NEW pattern):** `JsonNode.asBoolean()` on a wrong-type `met`
    (`{"met":"nope"}`) coerces to `false`, turning a malformed external response into a `CONDITION_NOT_MET`
    business outcome — violating the closed call-failure vocabulary. Fix: accept only `met.isBoolean()`,
    else `null` → `CallFailed`. **DO flag any `JsonNode.asX()` coercion that turns malformed external data
    into a business outcome; require a real type-check (`isBoolean`/`isTextual`/...) at trust boundaries.**
  - **R2 P2 read-path body-in-summary:** the new TEXT `last_response` was hydrated by the inline
    `job_states[]` summary path — same precedent as R9. Fix: body-less metadata projection
    (`TerraformJobStateMetadata` + `findMetadataByTaskId`); only the per-job `/state` detail endpoint pays
    the body I/O.
  - **R3 P1 judgment-tx column overflow (NEW pattern):** capturing the FULL condition body into
    `task_attempt.response` (a column written INSIDE the write-back tx2, unlike best-effort observation
    tables) means a large external body can fail `save`, roll back the completion judgment, and trap the
    pipeline in a lease-expiry loop. Fix: truncate the condition body to the column limit
    (`ConditionOperationBinding.RESPONSE_MAX_LENGTH`) before it enters the engine — condition-only; the
    terraform DISPATCH response (read back to drive polling) is deliberately NOT truncated. **DO flag any
    NEW unbounded external string routed into a judgment-tx column; bound it (truncate) or move it to a
    best-effort observation write.**
