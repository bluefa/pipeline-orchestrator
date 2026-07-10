---
name: codex-review
description: Cross-review the current branch with OpenAI Codex CLI (gpt-5.6-sol, reasoning xhigh) as an independent second model. Use for pre-PR sign-off and major-decision validation, especially on concurrency, transactions, and the ADR-016 invariants — where a single-model loop has blind spots. Run several rounds until no P0/P1 remains.
---

# Codex Review

Run the branch's changes through Codex CLI as an independent reviewer — a counter-opinion that a
single-model loop misses. Major decisions go through it; run **multiple rounds** until the verdict is
merge-ready (no P0/P1).

## Execution

1. **Full access**: pass `--dangerously-bypass-approvals-and-sandbox` so Codex can read the whole repo
   (`.claude/**`, untracked files, the diff) for skill-aware review.
2. **Pinned model**: always `-c model="gpt-5.6-sol" -c model_reasoning_effort="xhigh"` on the CLI — do not
   rely on `~/.codex/config.toml` defaults.
3. **Foreground/background Bash with `timeout: 600000`** and `</dev/null` (Codex blocks on stdin
   otherwise). Pipe the verdict back to the user verbatim with a 1–3 line summary on top.
4. **Review only**: the prompt must forbid edits. Never auto-apply Codex's suggestions — evaluate each.

## Command

```bash
codex exec \
  --dangerously-bypass-approvals-and-sandbox \
  -c model="gpt-5.6-sol" \
  -c model_reasoning_effort="xhigh" \
  "$(cat <<'PROMPT'
<the review prompt — see below>
PROMPT
)" </dev/null
```

## Prompt skeleton

Tell Codex to read the authorities first and review against them, cite `file:line`, severity P0/P1/P2,
prefer real failure modes over style, and end with a merge-ready yes/no:

- `docs/adr/016-install-delete-pipeline-domain-model.md` — the invariants (what must hold)
- `.claude/skills/spring-java21/SKILL.md` — §6 is the code checklist
- `docs/exception-strategy.md` — the failure contract
- `.claude/review-ledger.md` — the recurring findings to re-check
- `AGENTS.md` — hard repo rules

Focus, in order: ADR invariants (DB-only state, one-active-per-target on every terminalization path,
idempotent at-least-once, BLOCKED→READY→IN_PROGRESS→terminal, cancel/cascade, observation
write-only & attempt_no accounting); concurrency / transaction correctness (guarded-CAS, `@Version`);
exception strategy; spring-java21 §6; over-engineering / file count.

## Rounds

Round N reviews; fix the P0/P1; round N+1 verifies the fixes and re-sweeps. Stop when a round returns no
P0/P1. Record each round and any new pattern in `.claude/review-ledger.md` (the review-harness skill).
