# Shared helpers for the hook scripts in this directory. Sourced, never executed.
#
# Written to three constraints, none of them stylistic:
#
#   bash 3.2      no associative arrays, no `mapfile`, no ${!x@}.
#   no jq         absent from a default macOS and a default Windows. Field extraction is awk
#                 plus sed, and it handles exactly the flat string fields the hooks need.
#   Git Bash      on Windows tool_input.file_path arrives with BACKSLASHES, even though $PWD
#                 looks like /c/project. json_path normalises them; json_raw does not.
#
# And one rule that is easy to get wrong and impossible to notice:
#
#   set -u, NEVER set -o pipefail.
#
# A guard reads a value, filters it, matches. With pipefail, a filter that short-circuits —
# `grep -q`, `grep -m1`, `head -1` — raises SIGPIPE upstream, the pipeline reports failure, and
# the guard falls through to allow. It exits 0. Nothing is blocked, nothing is logged, and it
# looks exactly like a guard that found nothing.

# Read the whole hook payload from stdin into HOOK_INPUT.
#
# Every hook must call this, even the ones that ignore the payload: Claude Code writes the JSON to
# the script's stdin, and a script that never reads it can leave the writer blocked on a full
# pipe. Draining is not optional.
hook_read_stdin() {
  # Collapse real newlines to spaces. A JSON string cannot contain a raw newline — it must be
  # escaped as \n — so every literal newline in the payload is structural whitespace, and folding
  # it is lossless. Without this, a pretty-printed payload defeats every line-oriented extractor
  # below: awk processes the first line, does not find the key, and the hook logs or matches
  # nothing while still exiting 0. That is the failure mode this whole file is written against.
  HOOK_INPUT="$(cat | tr '\n' ' ')"
}

# json_raw <key> — the FIRST "key": "value" string value in the payload, still JSON-escaped.
#
# Why awk and not sed alone: a sed expression anchored with a leading `.*` is greedy, so it
# returns the LAST match in the line. `tool_input.command` and a `command` key elsewhere in the
# payload would silently swap places. awk cuts at the first occurrence; sed then reads the value
# off the front of what is left.
#
# Returns empty when the key is absent or its value is not a string (null, a number, an object).
# Callers treat empty as "nothing to check" and exit 0 — a guard that cannot read its input must
# not guess.
json_raw() {
  printf '%s' "$HOOK_INPUT" | awk -v k="\"$1\"" '
    { i = index($0, k); if (i == 0) next; print substr($0, i + length(k)); exit }
  ' | sed -E -n 's/^[[:space:]]*:[[:space:]]*"(([^"\\]|\\.)*)".*/\1/p'
}

# json_path <key> — the same value, normalised for path matching.
#
# A Windows path arrives JSON-escaped: C:\project\src reaches us as C:\\project\\src. One
# substitution undoes the escaping and the separator at once, so every pattern in every hook can
# be written with forward slashes and match on all three platforms.
#
# Do NOT try to unescape JSON generally here. Successive sed passes cannot do it correctly:
# `s/\\n/\n/` applied to C:\\new turns the second backslash and the `n` into a newline, and the
# path silently becomes something else. Paths only ever carry `\\`, so this is the whole job.
json_path() {
  json_raw "$1" | sed -E 's,\\\\,/,g'
}

# json_escape <string> — make a string safe to interpolate into a JSON string literal.
#
# Backslash first, always: escaping quotes first would then double-escape the backslashes it
# introduced. Control characters are dropped rather than encoded — a hook reason is one sentence
# of prose and has no business carrying a tab.
json_escape() {
  printf '%s' "$1" | sed -E 's/\\/\\\\/g; s/"/\\"/g' | tr -d '\000-\037'
}

# hook_deny <reason> — refuse the tool call and tell the agent why. PreToolUse only.
#
# The reason is the ONLY thing the agent sees. Write it so the next attempt can succeed: name what
# was blocked and what to do instead. "Blocked by policy" makes the agent retry the same call.
#
# Exit 0, not 2. Exit 2 also denies, but a JSON decision carries a structured reason and leaves
# stderr free for real errors.
hook_deny() {
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"%s"}}' \
    "$(json_escape "$1")"
  exit 0
}

# repo_root — the repository root, or the current directory outside a repo. Never fails.
repo_root() {
  git rev-parse --show-toplevel 2>/dev/null || pwd
}
