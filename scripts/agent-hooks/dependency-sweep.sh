#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

ROOT="$(repo_root)"
cd "$ROOT" || exit 0

# base-conocimiento/Makefile no tiene target "audit", y org.owasp:dependency-check-maven no esta
# configurado en su pom.xml -- versions:display-dependency-updates es el fallback siempre
# disponible (ver instrument-agent-java/references/hook-catalog.md, hook 4).
OUT="$(mvn -f base-conocimiento/pom.xml versions:display-dependency-updates 2>&1)"
STATUS=$?

# A missing Makefile target, a reactor that will not resolve, a typo in the command — all of them
# print nothing useful and exit non-zero, which without this check is INDISTINGUISHABLE from a
# clean repository. Say so on stderr, where the user sees it, and still exit 0: this hook reports,
# it never fails a session.
if [ $STATUS -ne 0 ]; then
  printf 'dependency-sweep: the audit command exited %s; no advisory report this session.\n' "$STATUS" >&2
  printf '%s\n' "$OUT" | head -n 3 >&2
  exit 0
fi

# Both commands can exit 0 whether or not they found anything, so the exit code says nothing. The
# findings are lines carrying either shape: an update arrow, or a CVE/severity marker. No
# findings, no output: a hook that prints "everything is fine" at every session start is noise
# that trains people to skip it.
FINDINGS="$(printf '%s\n' "$OUT" | grep -E -- '-> |CVE-[0-9]|(Critical|High|Medium|Low)' || true)"
[ -z "$FINDINGS" ] && exit 0

printf 'Dependency advisories for this repository (from a SessionStart hook, reported not enforced):\n\n'
printf '%s\n' "$FINDINGS"
printf '\nDo not add features on top of these without saying so.\n'
exit 0
