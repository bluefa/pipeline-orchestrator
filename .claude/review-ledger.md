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
| Purposeful names; **no abbreviations** in ANY identifier (class, method, field, variable) — reveal the role: `ImClient`→`InfraManagerClient`, `im`→`infraManager`, `seq`→`sequence`, `ttl`→`timeToLive`, `cve`→`constraintViolation`, catch `e`→`exception`. Allowed: `id`, `main(args)` (owner stated 3×) | owner | agent (recurring-review pattern 6) |
| Layered package convention: `entity / service / client / controller / dto / repository / utils` (entity also holds domain enums; dto holds transport values; app wiring at the root package) — no abbreviated package names like `im` | owner | rule (flag a new top-level pkg outside the set) |
| REST-layer exception handling lives in `advice/GlobalAdvice` (`@RestControllerAdvice`); domain failures stay values | owner | process |
| A global catch-all exception handler must **log the cause** (never return a generic body and drop the trace) | sonnet R5 | agent |

## Changelog

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
