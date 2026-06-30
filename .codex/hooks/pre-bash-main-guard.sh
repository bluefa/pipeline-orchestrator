#!/usr/bin/env bash
# PreToolUse hook: block edits/git writes on protected roots so implementation work happens in a
# dedicated worktree branch.
set -uo pipefail

mode="${1:-bash}"
input="$(cat 2>/dev/null || true)"
repo_root="${CODEX_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
branch="$(git -C "${repo_root}" branch --show-current 2>/dev/null || true)"
canonical_root="/Users/study/pipeline-orchestrator"

block_if_protected_root() {
  case "${branch}" in
    main|master)
      echo "BLOCK: edits/git writes are forbidden on ${branch}. Create a task worktree branch first." >&2
      exit 2
      ;;
  esac

  if [ "${repo_root}" = "${canonical_root}" ]; then
    echo "BLOCK: implementation work must happen in a task worktree, not ${canonical_root}." >&2
    exit 2
  fi
}

if [ "${mode}" = "--edit" ]; then
  block_if_protected_root
  exit 0
fi

case "${input}" in
  *"git add "*|*"git rm "*|*"git mv "*|*"git checkout "*|*"git switch "*|*"git commit"*|*"git push"*|*"git merge"*|*"git rebase"*|*"git reset --hard"*) ;;
  *) exit 0 ;;
esac

block_if_protected_root
exit 0
