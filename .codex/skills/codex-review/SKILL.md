---
name: codex-review
description: Cross-review the current branch with OpenAI Codex CLI (gpt-5.5, reasoning xhigh, fast service tier) as an independent second model. Use for pre-PR sign-off and major-decision validation, especially on concurrency, transactions, and ADR-016 invariants. Run rounds until no P0/P1 remains.
---

# Codex Review Skill

Run the current branch's changes through Codex CLI as an independent reviewer. Major decisions and
pre-PR sign-off go through it so the ADR invariants, transaction behavior, and recurring review
patterns get a second model pass.

## Execution principles

1. **Full access**: pass `--dangerously-bypass-approvals-and-sandbox` so Codex can read the whole repo
   (`.claude/**`, `.codex/**`, untracked files, and the diff) for skill-aware review.
2. **Pinned model and fast tier**: always pass
   `-c 'model="gpt-5.5"' -c 'model_reasoning_effort="xhigh"' -c 'service_tier="fast"'`. Do not rely on
   `~/.codex/config.toml` defaults; the review command itself owns these settings.
3. **Fresh base**: run `git fetch origin --quiet` before invoking Codex. Do not use
   `git fetch origin main`; that only refreshes `FETCH_HEAD` and can leave `refs/remotes/origin/main`
   stale.
4. **Foreground Bash with `timeout: 600000` and `</dev/null`**. Codex can block on stdin even when a
   prompt argument is provided. Pipe Codex stdout back to the user verbatim with a 1-3 line summary on
   top.
5. **Review only**: the prompt must forbid edits. Never auto-apply Codex suggestions; evaluate each
   finding first.

## Arg parsing

| Invocation | Behavior |
|---|---|
| `/codex-review` | Review `<upstream-or-origin/main>...HEAD` with a three-dot merge-base diff |
| `/codex-review uncommitted` | Review staged, unstaged, and untracked working tree changes |
| `/codex-review commit <sha>` | Review one commit |
| `/codex-review base=<branch>` | Override the comparison base |
| `/codex-review "<free text>"` | Append free text as extra reviewer instructions |

Combinable example: `/codex-review uncommitted "focus on cancel and terminalization paths"`.

## Command template

Use generic `codex exec` rather than `codex exec review` so the skill-aware prompt can control exactly
which authorities Codex reads and how untracked files are included.

```bash
git fetch origin --quiet

codex exec \
  --dangerously-bypass-approvals-and-sandbox \
  -c 'model="gpt-5.5"' \
  -c 'model_reasoning_effort="xhigh"' \
  -c 'service_tier="fast"' \
  "$(cat <<'PROMPT'
<REVIEW_PROMPT with DIFF_SCOPE filled in>
PROMPT
)" </dev/null
```

Fill `DIFF_SCOPE` inside the prompt based on the invocation:

- default -> `git diff <upstream-or-origin/main>...HEAD`, where `<upstream-or-origin/main>` is
  `git rev-parse --abbrev-ref --symbolic-full-name @{upstream}` if present, otherwise `origin/main`
- `uncommitted` -> `git diff HEAD`, plus `cat` every untracked file from
  `git ls-files --others --exclude-standard` because the diff only lists untracked paths
- `commit <sha>` -> `git show <sha>`
- `base=<branch>` -> `git diff <branch>...HEAD`

## Skill-aware review prompt

Pass this prompt to Codex, filling in `DIFF_SCOPE` and any extra reviewer instructions.

```text
You are an external reviewer for this repository. You are running as OpenAI Codex CLI,
cross-validating changes implemented by another agent.

=== STEP 1. Gather context ===
1. Run: DIFF_SCOPE. If the scope is `uncommitted`, also `cat` every untracked file listed by
   `git ls-files --others --exclude-standard`.
2. Collect the changed file paths and a short change summary.
3. Read every authority below before reviewing.

Always read:
- AGENTS.md — hard repository rules
- docs/adr/016-install-delete-pipeline-domain-model.md — domain invariants
- .claude/skills/spring-java21/SKILL.md — Java/Spring implementation and review checklist
- docs/exception-strategy.md — failure boundary and ErrorCode contract
- .claude/review-ledger.md — recurring findings and owner preferences
- .claude/skills/review-harness/SKILL.md — how recurring findings are recorded/promoted

If the diff touches `.codex/**`, also read the corresponding `.codex/skills/**` files to verify the
Codex-facing mirror.

=== STEP 2. Review ===
Focus, in order:
1. ADR-016 invariants: DB-only state, one-active-per-target on every terminalization path,
   idempotent at-least-once behavior, BLOCKED -> READY -> IN_PROGRESS -> terminal progression,
   cancel/cascade behavior, observation write-only semantics, and attemptNumber accounting.
2. Concurrency and transactions: guarded-CAS over `@Version` alone, correct transaction ownership, and
   no hidden wrapping transactions in tests.
3. Exception strategy: external-call failures are translated at the boundary; business outcomes are
   values, never thrown.
4. spring-java21 section 6 checklist.
5. Review-harness recurring patterns: guarded-CAS, targeted catch, no-hidden-test-tx,
   exhaustive-switch, trust-boundary nulls, index coverage, interface justification, and no
   abbreviations.

Classify findings as P0, P1, or P2:
- P0: correctness/security/data-loss issue that must block immediately.
- P1: merge-blocking bug, invariant break, or hard-rule violation.
- P2: non-blocking cleanup, clarity, or maintainability issue.

=== STEP 3. Output format ===

## Authorities read
- <path> — <one-line reason>

## Summary
- Findings: P0 N / P1 N / P2 N
- Most important: <one line>

## P0
Write "None" if empty. Otherwise use:
### <file:line> — <title>
<evidence>
<suggestion>

## P1
Same format.

## P2
Same format.

## Verdict
- Mergeable: Yes / Conditional / No
- Conditions: <if any>

=== Constraints ===
- Do not modify code. Review only.
- Cite concrete file:line evidence.
- Prefer real failure modes over style.
- No speculation; if unsure, mark as needs verification.
- Write the output in Korean unless the user explicitly requested another language.
```

## Rounds

Round N reviews; fix P0/P1; round N+1 verifies fixes and re-sweeps. Stop when a round returns no
P0/P1. Record each round and any new repeated pattern in `.claude/review-ledger.md` through the
review-harness skill.
