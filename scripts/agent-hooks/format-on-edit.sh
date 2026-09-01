#!/usr/bin/env bash
# `set -u` only. See the note in secret-read-guard.sh: pipefail turns a short-circuiting filter
# into a silent failure.
set -u

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
. "$DIR/_lib.sh"

hook_read_stdin

FILE="$(json_path file_path)"
[ -z "$FILE" ] && exit 0

# Java only. Spotless has nothing to say about .md, .xml or .properties in the common
# configuration, and calling Maven for those pays the JVM+reactor startup cost for no change.
case "$FILE" in
  *.java) ;;
  *) exit 0 ;;
esac

# Resolve BOTH paths physically before comparing them. `git rev-parse` and the payload can spell
# the same directory differently, and a textual strip then leaves REL unchanged, so the hook exits
# 0 having formatted nothing. A hook that silently does nothing passes every check in Phase 5.
ROOT="$(cd "$(repo_root)" 2>/dev/null && pwd -P)" || exit 0
FDIR="$(cd "$(dirname "$FILE")" 2>/dev/null && pwd -P)" || exit 0   # also covers a deleted file
FILE="$FDIR/$(basename "$FILE")"

cd "$ROOT" || exit 0

# Quoting "$ROOT" inside ${...#} turns off pattern matching, so a repo path containing [ ] * or ?
# is stripped literally instead of being read as a glob.
case "$FILE" in
  "$ROOT"/*) REL="${FILE#"$ROOT"/}" ;;
  *) exit 0 ;;                    # outside this repo; not ours to format
esac

[ -f "$REL" ] || exit 0

# Este es un monorepo con un solo proyecto Java hoy (base-conocimiento/); un .java fuera de esa
# carpeta no tiene pom.xml que lo cubra. Si el monorepo suma otro proyecto Java, esta condicion
# (y el -f de abajo) necesitan resolverse por prefijo en vez de estar fijos.
case "$REL" in
  base-conocimiento/*) ;;
  *) exit 0 ;;
esac

# Regex-escape the relative path, then make every separator tolerant of the platform Maven
# actually sees. Order matters: escape metacharacters FIRST so the "/" substitution in the second
# pass is not itself re-escaped by the first.
ESCAPED="$(printf '%s' "$REL" \
  | sed -E 's/[.\+*?^$(){}|]/\\&/g' \
  | sed -E 's,/,[/\\\\],g')"

OUT="$(mvn -q -f "base-conocimiento/pom.xml" spotless:apply "-DspotlessFiles=.*${ESCAPED}" 2>&1)"
STATUS=$?

if [ $STATUS -ne 0 ]; then
  # stderr on a PostToolUse hook is shown, not acted on. Keep it to a few lines: the whole point
  # is that somebody notices the formatter is broken, not that they read a full Maven log here.
  printf 'format-on-edit: mvn spotless:apply exited %s for %s\n' "$STATUS" "$REL" >&2
  printf '%s\n' "$OUT" | head -n 5 >&2
fi

exit 0
