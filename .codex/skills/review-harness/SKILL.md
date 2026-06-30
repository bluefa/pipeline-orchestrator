---
name: review-harness
description: The recurring-findings harness for pipeline-orchestrator. Use when recording a review finding (codex/opus/human) or an owner preference, when deciding whether a repeated finding should become an automated check, and before committing or reviewing to run the promoted detections. Maintains .claude/review-ledger.md and promotes anything seen twice into a rule-based or agent-based check so a human never makes the same note three times.
---

# Review Harness — turn repeated findings into automated detection

A learning loop. Every review finding and every stated preference is recorded; anything that recurs
**twice** becomes an automated check. The goal: a finding is made by a human at most twice — the third
time, the harness catches it.

## The loop

1. **Record.** A new review finding (codex / opus / human) or an owner preference → add a row to the
   **watch-list** in [`.claude/review-ledger.md`](../../../.claude/review-ledger.md). If it matches an existing
   row, bump its occurrence count and append the new source (round / author) — keep the trail; it is
   what justifies promotion.
2. **Promote at ≥ 2.** When a row reaches two occurrences, move it to **Promoted** and wire a detection:
   - **Rule-based** — a syntactically-detectable pattern → add a check to
     [`scripts/recurring-check.sh`](../../../scripts/recurring-check.sh). Keep it low-noise: it must not
     fire on the existing, correct code.
   - **Agent-based** — a semantic pattern that needs judgment ("is this CAS actually guarded?", "is this
     interface justified?") → add a bullet to the
     [`recurring-review`](../../agents/recurring-review.md) agent.
   Prefer rule-based when a grep can decide it; use agent-based when it cannot.
3. **Detect.**
   - **Rule-based runs automatically.** `scripts/recurring-check.sh` runs on staged Java via the
     pre-commit hook (advisory) and by hand (`bash scripts/recurring-check.sh --staged`). When the
     staged diff touches a status transition or a new interface, the script also **prints a reminder**
     to run the agent.
   - **Agent-based is process-driven**, not automatic — spawn the `recurring-review` agent before
     committing a diff that touches guarded-CAS / exception-boundary / interface code. The hook's
     reminder is the cue.

## When to use

- **After any review** (codex / opus / human) or **when the owner states a preference** → record it.
- **Before a commit / PR** → run the rule-based check; if the change touches guarded-CAS /
  exception-boundary / interface code, spawn the `recurring-review` agent.

## Rules

- One row per concept — bump, never duplicate.
- A rule-based grep must be precise enough to stay silent on correct code; if it cannot, use the agent.
- An intentional exception to a rule is annotated inline with `// harness-allow: <rule> — <reason>`; the
  script skips those lines. Marking intent is required, not optional.
- Promotions are cheap to add and cheap to delete. Remove a check if the pattern stops recurring or the
  rule churns false positives — a noisy harness gets ignored.

## Current state

Promoted detections and the watch-list of owner preferences live in
[`.claude/review-ledger.md`](../../../.claude/review-ledger.md).
