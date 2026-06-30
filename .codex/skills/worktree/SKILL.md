---
name: worktree
description: Set up a dedicated git worktree and branch before Codex implementation work in pipeline-orchestrator. Use before editing code unless already inside a task-specific worktree.
user_invocable: true
---

# Worktree Setup

Use this skill before implementation work. The repository rule is: code changes happen in a dedicated
task worktree, not in the canonical checkout or on `main`/`master`.

## Inputs

- `topic`: short kebab-case task name, for example `codex-review-skill`
- `prefix`: branch prefix; default `codex`

## Procedure

1. Start from the canonical repository root:

   ```bash
   cd /Users/study/pipeline-orchestrator
   git fetch origin --quiet
   ```

2. Pick the base:
   - If the current branch has an upstream, use that upstream. This keeps feature-branch work based on
     the active feature branch.
   - Otherwise use `origin/main`.

   ```bash
   base_ref="$(git rev-parse --abbrev-ref --symbolic-full-name @{upstream} 2>/dev/null || printf 'origin/main')"
   ```

3. Create the worktree and branch:

   ```bash
   branch_name="{prefix}/{topic}"
   worktree_path="../pipeline-orchestrator-{topic}"
   git worktree add "${worktree_path}" -b "${branch_name}" "${base_ref}"
   cd "${worktree_path}"
   ```

4. Verify the setup before editing:

   ```bash
   git status --short --branch
   git rev-parse --show-toplevel
   git branch --show-current
   git worktree list
   ```

5. Continue all implementation, tests, commits, and pushes from the new worktree.

## Rules

- Do not create a second worktree if the current directory is already a task-specific worktree.
- Do not start implementation work on `main` or `master`.
- Prefer branch names under `codex/` for Codex-authored work.
- Run `mvn test` before finishing unless the task explicitly does not touch repository behavior and the
  user accepts skipping verification.
