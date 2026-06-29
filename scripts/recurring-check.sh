#!/usr/bin/env bash
#
# Rule-based detection of recurring review findings — see .claude/review-ledger.md.
# Low-noise by design: silent on the existing, correct code; only flags a regression of a promoted
# pattern. An intentional exception is annotated inline with `// harness-allow: <rule> — <reason>`
# and skipped. Portable to Bash 3.2 (the macOS default) — no `mapfile`, no empty-array expansion.
#
# Modes:
#   (no args)            pre-commit hook: reads PreToolUse JSON on stdin, acts only on `git commit`
#   --staged             check staged *.java
#   <file> [file...]     check the given files (resolved to absolute BEFORE any cd, so a subdir works)
#
# Exit 0 always (advisory). STRICT=1 -> exit 1 on findings (for CI).

set -uo pipefail

files=()
mode="${1:-}"

if [[ "$mode" != "--staged" && -n "$mode" ]]; then
  # Explicit file args: resolve to absolute now, while still in the caller's directory.
  for a in "$@"; do
    if [[ -f "$a" ]]; then
      files+=("$(cd "$(dirname "$a")" && pwd)/$(basename "$a")")
    fi
  done
else
  # Git-based modes need the repo root.
  root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
  cd "$root" || exit 0
  if [[ "$mode" != "--staged" ]]; then
    # Hook mode: stdin is the PreToolUse JSON; only a `git commit` triggers a scan.
    grep -q 'git commit' <<<"$(cat 2>/dev/null || true)" 2>/dev/null || exit 0
  fi
  while IFS= read -r f; do
    [[ -n "$f" ]] && files+=("$f")
  done < <(git diff --cached --name-only --diff-filter=ACM -- '*.java' 2>/dev/null)
fi

[[ ${#files[@]} -eq 0 ]] && exit 0   # guard before any "${files[@]}" expansion (Bash 3.2 + set -u)

report=""
semantic=0
add() { report+="  [$1] $2:$3 — $4"$'\n'; }

for f in "${files[@]}"; do
  [[ -f "$f" ]] || continue

  # no-hidden-test-tx: a test that runs inside a wrapping transaction (@DataJpaTest's default, or
  # @Transactional) without opting out. A pure repository test opts out with a harness-allow note.
  if [[ "$f" == *Test.java || "$f" == */test/* ]]; then
    if ! grep -q 'NOT_SUPPORTED' "$f" 2>/dev/null; then
      while IFS=: read -r ln _; do
        add "no-hidden-test-tx" "$f" "$ln" "test runs in a wrapping tx → propagation=NOT_SUPPORTED or @SpringBootTest (or '// harness-allow: no-hidden-test-tx' for a pure repository test)"
      done < <(grep -nE '^[[:space:]]*@(DataJpaTest|Transactional)\b' "$f" 2>/dev/null | grep -v harness-allow)
    fi
  fi

  # exhaustive-switch: a switch `default` label can silently swallow a new enum value. Anchored to a
  # line-leading label to avoid matching strings/comments.
  while IFS=: read -r ln _; do
    add "exhaustive-switch" "$f" "$ln" "switch default arm → enumerate the cases so a new enum value is a compile error (harness-allow for an intentional open-world default)"
  done < <(grep -nE '^[[:space:]]*default[[:space:]]*(->|:)' "$f" 2>/dev/null | grep -v harness-allow)

  # targeted-catch (rule half): a genuinely broad catch almost always masks unrelated failures.
  while IFS=: read -r ln _; do
    add "targeted-catch" "$f" "$ln" "broad catch → target the cause and rethrow the rest"
  done < <(grep -nE 'catch[[:space:]]*\([[:space:]]*(Exception|Throwable)[[:space:]]' "$f" 2>/dev/null | grep -v harness-allow)

  # Semantic-area reminder: status transitions and new interfaces need the recurring-review AGENT.
  if grep -qE 'setStatus[[:space:]]*\(|^[[:space:]]*(public|private|protected)?[[:space:]]*interface[[:space:]]' "$f" 2>/dev/null; then
    semantic=1
  fi
done

if [[ -n "$report" ]]; then
  echo "review-harness — advisory findings (see .claude/review-ledger.md):"
  printf '%s' "$report"
fi
if [[ $semantic -eq 1 ]]; then
  echo "review-harness — staged change touches a status transition / a new interface: run the recurring-review agent (semantic guarded-CAS & interface-justification checks the grep cannot make)."
fi
[[ -n "$report" && "${STRICT:-0}" == "1" ]] && exit 1
exit 0
