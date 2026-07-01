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
| **targeted-catch** — a boundary `catch` must verify it is the intended cause and rethrow the rest | (1) spring-java21 §5.7 · (2) R1 `PipelineCreator` broad `DataIntegrityViolationException` (codex + opus) | agent (+ rule flags `Exception`/`Throwable`) | a catch recovering from a whole class without discriminating |
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

## Watch-list (1 occurrence — recorded, promote on the next hit)

| Preference / finding | Source | Promote-to |
|---|---|---|
| MySQL is the target DB; schema is JPA-generated (`@Table`/`@Index`/`@UniqueConstraint`), no Flyway / raw SQL migrations | owner | rule (flag added Flyway dep or `*.sql` under resources) |
| Keep the absolute file count down; prefer a static util / nested type to a new file | owner; spring-java21 §10 | agent |
| An `interface` must earn its place: a real external boundary (e.g. `InfraManagerClient`, prod + test fake) OR genuine N-impl polymorphism (e.g. `TaskType`: Terraform/Condition). A single-impl interface a concrete class or static util would serve is flagged | owner; spring-java21 §10 | agent (interface-justification) |
| Business failures are values (`ErrorCode`); only external/infra failures are exceptions | owner; spring-java21 §5.7; exception-strategy.md | agent |
| Extensibility seams are documented, not pre-built (YAGNI) | owner; extensibility.md | agent |
| Derive "done" from the ADR (`docs/acceptance-criteria.md`); don't ask for sign-off | owner | process |
| Respond to the owner in Korean | owner | process |
| Prefer `Stream`/`IntStream.range` (enumerate) over a `for` loop where it reads cleanly | owner | agent |
| Purposeful names; **no abbreviations** in ANY identifier (class, method, field, variable) — reveal the role: `ImClient`→`InfraManagerClient`, `im`→`infraManager`, `seq`→`sequence`, `ttl`→`timeToLive`, `cve`→`constraintViolation`, catch `e`→`exception`. Allowed: `id`, `main(args)` (owner stated 3×). Role-based collection names (`tasks`, `pipelines`, `settings`) are correct — reveal-the-role, not echo-the-type (ADR-021 retro #1 folded in here; owner keeps role names) | owner | agent (recurring-review pattern 6) |
| Layered package convention: `entity / service / client / controller / dto / repository / utils` (entity also holds domain enums; dto holds transport values; app wiring at the root package) — no abbreviated package names like `im` | owner | rule (flag a new top-level pkg outside the set) |
| REST-layer exception handling is `GlobalAdvice` (`@RestControllerAdvice`); domain failures stay values. **INCONSISTENCY to resolve (owner):** the code has it in `advice/` (PR #9) but AGENTS.md #6 text still says `controller/` holds the advice. No rule promoted until the package of record is settled | owner; AGENTS.md #6 vs code | process (owner to reconcile doc ↔ code) |
| A global catch-all exception handler must **log the cause** (never return a generic body and drop the trace) | sonnet R5 | agent |
| DTO built with a positional `new` (adjacent same-type or boolean args) → `@Builder` for call-site clarity | ADR-021 retro #3 | agent |
| Duration-typed field suffix convention: omit for `Interval`/`Timeout`/`Sleep`/`Retry`; spell out when ambiguous (`backoffBase`/`backoffMax`) — **PENDING owner decision** before promotion | ADR-021 retro #15 | (undecided) |
| VS Code Lombok `@NonNull` false warnings → `.vscode/settings.json` `"java.compile.nullAnalysis.mode":"disabled"` (editor-only, code-unrelated) | ADR-021 retro #16 | none (editor config) |

## Changelog

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
