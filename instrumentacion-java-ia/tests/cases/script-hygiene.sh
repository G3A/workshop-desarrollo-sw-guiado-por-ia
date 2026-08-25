# Structural invariants of the delivered scripts.
#
# Not every rule these scripts follow is observable from the outside. `set -o pipefail` is the
# clearest case: it is what turned a short-circuiting filter into a guard that silently allowed
# everything, but after the guards were structured to read their result from captured output
# rather than from a pipeline's exit status, reintroducing it changes no behaviour a case file
# could see. The rule still holds — the next filter added to a pipeline would resurrect the bug —
# so it is asserted here directly, against the text of the script.

want() { # want <label> <actual> <expected>
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); [ -n "${VERBOSE:-}" ] && printf '  ok   %s\n' "$1"
  else FAIL=$((FAIL+1)); FAILED_CASES="$FAILED_CASES
    [script-hygiene] $1 — expected $3, got $2"; printf '  FAIL %s — expected %s, got %s\n' "$1" "$3" "$2"; fi
}

# `set -o pipefail` must appear in no delivered script. It is discussed in comments, which is why
# this greps for the setting rather than the word.
want 'no script enables pipefail' \
  "$(grep -lE '^[[:space:]]*set[[:space:]].*pipefail' "$WORK/hooks"/*.sh 2>/dev/null | wc -l | tr -d ' ')" 0

# Every executable hook sets -u and nothing else.
for f in "$WORK/hooks"/*.sh; do
  n="$(basename "$f")"
  [ "$n" = "_lib.sh" ] && continue
  want "$n sets -u only"       "$(grep -cE '^set -u$' "$f" | tr -d ' ')" 1
  want "$n has a shebang"      "$(head -1 "$f" | grep -c '^#!/usr/bin/env bash' | tr -d ' ')" 1
  # A hook that never reads stdin can leave Claude Code blocked writing to a full pipe.
  want "$n drains stdin"       "$(grep -c 'hook_read_stdin' "$f" | tr -d ' ')" 1
  want "$n sources _lib.sh"    "$(grep -c '\. "\$DIR/_lib\.sh"' "$f" | tr -d ' ')" 1
done

# _lib.sh is sourced, so it must carry no shebang and set nothing.
want '_lib.sh has no shebang'  "$(head -1 "$WORK/hooks/_lib.sh" | grep -c '^#!' | tr -d ' ')" 0
want '_lib.sh sets nothing'    "$(grep -cE '^set ' "$WORK/hooks/_lib.sh" | tr -d ' ')" 0
# Its documentation must survive the cut — a file starting at a bare function tells the next
# reader nothing about the three constraints it exists to satisfy.
want '_lib.sh keeps its docs'  "$(head -3 "$WORK/hooks/_lib.sh" | grep -c '^#' | tr -d ' ')" 3

# Anything path-shaped must go through json_path, which normalises the backslashes a Windows
# payload arrives with. json_raw does not.
for n in secret-read-guard format-on-edit block-dangerous-bash version-pin-guard generated-files-guard; do
  want "$n uses json_path for paths" \
    "$(grep -c 'json_path' "$WORK/hooks/$n.sh" | tr -d ' ' | awk '{print ($1>0)?1:0}')" 1
done
