#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

ROOT="$(repo_root)"
LOG_DIR="$ROOT/logs"
LOG="$LOG_DIR/audit.log"
MAX_BYTES=5242880   # 5 MiB. One rotation, one generation kept: this is a local trail, not archival.

mkdir -p "$LOG_DIR" 2>/dev/null || exit 0

# Rotate before appending, so the cap is a cap and not a suggestion. `wc -c` on a missing file
# fails; the fallback of 0 means "nothing to rotate".
SIZE="$( (wc -c < "$LOG") 2>/dev/null || echo 0 )"
if [ "${SIZE:-0}" -gt "$MAX_BYTES" ]; then
  mv -f "$LOG" "$LOG.1" 2>/dev/null || true
fi

# Tab-separated so the line stays greppable even though the last column is JSON. The JSON payload
# is single-line, so it cannot break the record.
TS="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
SESSION="$(json_raw session_id)"
EVENT="$(json_raw hook_event_name)"
TOOL="$(json_raw tool_name)"

# tool_input is an object, not a string, so json_raw cannot read it: pull the raw object text with
# awk by balancing braces from the key onward. A truncated or unparseable payload logs an empty
# column rather than a wrong one — this hook never blocks, so it never guesses either.
INPUT="$(printf '%s' "$HOOK_INPUT" | awk '
  { i = index($0, "\"tool_input\""); if (i == 0) exit
    s = substr($0, i); j = index(s, "{"); if (j == 0) exit
    depth = 0; instr = 0; esc = 0
    for (k = j; k <= length(s); k++) {
      c = substr(s, k, 1)
      if (esc) { esc = 0; continue }
      if (c == "\\") { esc = 1; continue }
      if (c == "\"") { instr = !instr; continue }
      if (instr) continue
      if (c == "{") depth++
      else if (c == "}") { depth--; if (depth == 0) { print substr(s, j, k - j + 1); exit } }
    }
  }')"

printf '%s\t%s\t%s\t%s\t%s\n' "$TS" "${SESSION:--}" "${EVENT:--}" "${TOOL:--}" "${INPUT:--}" >> "$LOG"
exit 0
