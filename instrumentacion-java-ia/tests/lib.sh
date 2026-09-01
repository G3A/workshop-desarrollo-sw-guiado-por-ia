# Shared helpers for the hook test suite. Sourced by run.sh; never executed directly.
#
# NOTE FOR ANYONE WHO INSTALLS THIS REPO'S OWN HOOKS HERE: the case files below contain the
# literal strings `.env`, `id_rsa` and `secrets.json`, because that is what they test. The secret
# read-guard will refuse to let an agent read them. That is the guard working, not a broken test.

SKILL_TEMPLATES="$REPO/sdlc-ia/skills/instrument-agent-java/templates/hooks"

PASS=0; FAIL=0; FAILED_CASES=""

# Cut each template at its instruction marker and resolve the placeholders. This is exactly what
# Phase 4 of the skill does, so a template whose header markers drift breaks the suite too — which
# is the point.
materialize() {
  mkdir -p "$WORK/hooks"
  for f in "$SKILL_TEMPLATES"/*.sh.template; do
    n="$(basename "$f" .template)"
    sed '/^# ---8<---/,$!d' "$f" | tail -n +2 > "$WORK/hooks/$n"
    sed -i.bak \
      -e 's/{{PROTECTED_BRANCHES}}/main|master/g' \
      -e 's|{{POM_PATH}}|pom.xml|g' \
      -e 's|{{SWEEP_COMMAND}}|true|g' \
      "$WORK/hooks/$n"
    rm -f "$WORK/hooks/$n.bak"
    chmod +x "$WORK/hooks/$n"
  done
  # Every delivered script must parse, including the two the suite does not exercise.
  for f in "$WORK/hooks"/*.sh; do
    bash -n "$f" || { echo "SYNTAX ERROR in $(basename "$f")"; exit 1; }
  done
  # An unresolved placeholder is a template that grew a value nobody wired up.
  if grep -rq '{{' "$WORK/hooks"; then
    echo "UNRESOLVED PLACEHOLDER:"; grep -rn '{{' "$WORK/hooks"; exit 1
  fi
}

# Escape a string for embedding in a JSON string literal. Backslash first, then quotes, then real
# newlines become the two characters \ and n — which is precisely how Claude Code delivers a
# multi-line Bash command, and the case the guards used to fail on.
# `tr '\n' '\001'` then `sed 's/\001/\\n/g'` looks equivalent and is not: BSD sed does not read
# \001 in a PATTERN as an octal escape, so the substitution never fires and the newline vanishes
# into a control character — silently, which is the failure mode this whole suite exists for.
# sed does the character escaping; awk joins the lines.
jesc() {
  printf '%s' "$1" \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
    | awk 'NR>1 { printf "\\n" } { printf "%s", $0 }'
}

payload_bash()       { printf '{"tool_name":"Bash","tool_input":{"command":"%s"}}' "$(jesc "$1")"; }
payload_powershell() { printf '{"tool_name":"PowerShell","tool_input":{"command":"%s"}}' "$(jesc "$1")"; }
payload_file()       { printf '{"tool_name":"%s","tool_input":{"file_path":"%s"}}' "$1" "$(jesc "$2")"; }
payload_raw()        { printf '%s' "$1"; }

# Run a hook against a payload. Sets OUT, ERR and RC.
run_hook() {
  OUT="$(printf '%s' "$2" | bash "$WORK/hooks/$1" 2>"$WORK/stderr")"
  RC=$?
  ERR="$(cat "$WORK/stderr")"
}

# Classify what the hook decided, from its stdout alone.
verdict() {
  case "$OUT" in
    *'"permissionDecision":"deny"'*) printf 'deny' ;;
    *'"decision":"block"'*)          printf 'warn' ;;
    "")                              printf 'silent' ;;
    *)                               printf 'output' ;;
  esac
}

# check <hook> <expected: deny|warn|silent|output> <label> <payload>
check() {
  run_hook "$1" "$4"
  got="$(verdict)"
  if [ "$got" = "$2" ] && [ "$RC" -eq 0 ]; then
    PASS=$((PASS + 1))
    [ -n "${VERBOSE:-}" ] && printf '  ok   %-7s %s\n' "$got" "$3"
  else
    FAIL=$((FAIL + 1)); FAILED_CASES="$FAILED_CASES
    [$1] $3 — expected $2, got $got (exit $RC)"
    printf '  FAIL %-7s %s  (expected %s, exit %s)\n' "$got" "$3" "$2" "$RC"
    [ -n "$ERR" ] && printf '         stderr: %s\n' "$ERR"
  fi
}

# A hook must never fail the session, whatever it decides.
assert_exit_zero() { [ "$RC" -eq 0 ]; }
